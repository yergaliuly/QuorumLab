package io.github.yergaliuly.quorumlab.storage.wal;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.yergaliuly.quorumlab.storage.wal.WalStateTest.*;

import io.github.yergaliuly.quorumlab.core.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Actual filesystem/child-JVM tests, deliberately Linux-only: Windows is not a supported durability target. */
@EnabledOnOs(OS.LINUX)
class FileWalLinuxTest {
    @TempDir Path temporary;

    @Test void walTestFilesResideOnBuildFilesystemNotSystemTmpfs() throws Exception {
        Path buildFiles = Path.of("build/tmp/test-files").toAbsolutePath().normalize();
        assertTrue(temporary.toAbsolutePath().normalize().startsWith(buildFiles), temporary.toString());
        String filesystem = Files.getFileStore(temporary).type();
        assertNotEquals("tmpfs", filesystem);
        assertNotEquals("ramfs", filesystem);
        System.out.println("WAL test filesystem: " + filesystem + "; data: " + temporary);
    }

    @Test void createsPersistsAndReopensRealFiles() throws Exception {
        Path directory = initialized();
        try (var wal = FileWal.open(directory)) {
            wal.persist(List.of(term(2), entry(2, 2), new WalRecord.SetCommitIndex(2)));
        }
        try (var wal = FileWal.open(directory)) {
            assertEquals(2, wal.state().hardState().currentTerm());
            assertEquals(2, wal.state().hardState().commitIndex());
            assertEquals(List.of(entry(1, 1), entry(2, 2)), wal.state().entries());
        }
        assertFalse(Files.exists(directory.resolve(FileWal.TEMP)));
        assertTrue(Files.exists(directory.resolve("writer.lock")));
    }

    @Test void rejectsSecondWriterInSameAndDifferentJvm() throws Exception {
        Path directory = initialized();
        try (var ignored = FileWal.open(directory)) {
            assertThrows(IOException.class, () -> FileWal.open(directory));
            assertEquals(74, runChild(directory, "probeLock"));
        }
        try (var wal = FileWal.open(directory)) { assertEquals(1, wal.state().hardState().commitIndex()); }
    }

    @Test void truncatesUncheckpointedTailButFailsOnCorruptionOfLastDurableRecord() throws Exception {
        Path directory = initialized(); Path segment = directory.resolve(FileWal.SEGMENT);
        byte[] durable = Files.readAllBytes(segment);
        Files.write(segment, new byte[]{0, 1, 2, 3, 4}, StandardOpenOption.APPEND);
        try (var wal = FileWal.open(directory)) { assertEquals(1, wal.state().entries().size()); }
        assertArrayEquals(durable, Files.readAllBytes(segment));
        durable[durable.length - 1] ^= 1; Files.write(segment, durable);
        assertThrows(WalRecoveryException.class, () -> FileWal.open(directory));
        assertArrayEquals(durable, Files.readAllBytes(segment));
    }

    @Test void refusesCreateOverMissingSegmentWithExistingCheckpoint() throws Exception {
        Path directory = initialized(); byte[] checkpoint = Files.readAllBytes(directory.resolve(FileWal.CHECKPOINT));
        Files.delete(directory.resolve(FileWal.SEGMENT));
        assertThrows(IOException.class, () -> FileWal.create(directory));
        assertArrayEquals(checkpoint, Files.readAllBytes(directory.resolve(FileWal.CHECKPOINT)));
        assertFalse(Files.exists(directory.resolve(FileWal.SEGMENT)));
    }

    @Test void realEnospcFromDevFullProducesFailureAndNoCheckpointAdvance() throws Exception {
        Path directory = initialized(); byte[] before = Files.readAllBytes(directory.resolve(FileWal.CHECKPOINT));
        var queue = new ArrayDeque<RaftEvent>();
        // The WAL directory is not filled. /dev/full supplies a real kernel ENOSPC on the second write.
        var io = new NioWalIo(directory) {
            @Override public Handle open(String name, boolean createNew) throws IOException {
                var delegate = super.open(name, createNew);
                if (!name.equals(FileWal.SEGMENT)) return delegate;
                return new Handle() {
                    boolean partialWritten;
                    @Override public int read(ByteBuffer target, long position) throws IOException { return delegate.read(target, position); }
                    @Override public int write(ByteBuffer source, long position) throws IOException {
                        if (!partialWritten) {
                            var partial = source.duplicate(); partial.limit(partial.position() + Math.min(5, partial.remaining()));
                            int count = delegate.write(partial, position); source.position(source.position() + count);
                            partialWritten = true; return count;
                        }
                        try (var full = FileChannel.open(Path.of("/dev/full"), StandardOpenOption.WRITE)) { return full.write(source); }
                    }
                    @Override public long size() throws IOException { return delegate.size(); }
                    @Override public void truncate(long size) throws IOException { delegate.truncate(size); }
                    @Override public void force() throws IOException { delegate.force(); }
                    @Override public void close() throws IOException { delegate.close(); }
                };
            }
        };
        try (var wal = FileWal.open(io)) {
            new WalStorage(wal, queue).persist(new Effect.PersistBatch(new OperationId(1, 1),
                    new HardState(2, Optional.empty(), 1)));
            var failure = assertInstanceOf(RaftEvent.PersistFailed.class, queue.remove());
            assertTrue(failure.reason().contains("No space left on device"), failure.reason());
            assertTrue(queue.isEmpty());
            assertThrows(IllegalStateException.class, wal::state);
        }
        assertArrayEquals(before, Files.readAllBytes(directory.resolve(FileWal.CHECKPOINT)));
        try (var wal = FileWal.open(directory)) { assertEquals(1, wal.state().hardState().currentTerm()); }
    }

    @ParameterizedTest @ValueSource(strings = {"beforeWalWrite", "partialWalWrite", "afterWalWrite",
            "beforeWalForce", "afterWalForce", "beforeCheckpointWrite", "partialCheckpointWrite",
            "afterCheckpointWrite", "beforeCheckpointForce", "afterCheckpointForce", "beforeRename",
            "afterRename", "beforeDirectoryForce", "afterDirectoryForce", "afterAck"})
    void processHaltAtDurabilityBoundaryRestoresWholeOldOrNewBatch(String boundary) throws Exception {
        Path directory = initialized();
        assertEquals(73, runChild(directory, boundary));
        // A process halt keeps the OS alive: an already completed rename remains visible.
        boolean newer = Set.of("afterRename", "beforeDirectoryForce", "afterDirectoryForce", "afterAck").contains(boundary);
        try (var wal = FileWal.open(directory)) {
            assertEquals(newer ? 2 : 1, wal.state().hardState().currentTerm(), boundary);
            assertEquals(newer ? 2 : 1, wal.state().hardState().commitIndex(), boundary);
            assertEquals(newer ? 2 : 1, wal.state().entries().size(), boundary);
            wal.persist(List.of(term(3)));
        }
        try (var wal = FileWal.open(directory)) { assertEquals(3, wal.state().hardState().currentTerm()); }
    }

    @Test void seededHistoryMatchesIndependentReferenceAcrossRepeatedReopen() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("history"));
        try (var ignored = FileWal.create(directory)) { }
        var random = new Random(66821);
        var expected = new ArrayList<WalRecord.AppendEntry>();
        long term = 0; long commit = 0;
        for (int step = 0; step < 100; step++) {
            var batch = new ArrayList<WalRecord>();
            batch.add(term(++term));
            if (expected.size() > commit && random.nextBoolean()) {
                int from = (int) commit + random.nextInt(expected.size() - (int) commit);
                batch.add(new WalRecord.TruncateSuffix(from + 1L));
                expected.subList(from, expected.size()).clear();
            }
            int additions = 1 + random.nextInt(3);
            for (int i = 0; i < additions; i++) {
                var entry = entry(expected.size() + 1L, term); expected.add(entry); batch.add(entry);
            }
            commit += random.nextInt(expected.size() - (int) commit + 1);
            batch.add(new WalRecord.SetCommitIndex(commit));
            try (var wal = FileWal.open(directory)) { wal.persist(batch); }
            try (var wal = FileWal.open(directory)) {
                assertEquals(new HardState(term, Optional.empty(), commit), wal.state().hardState(), "step " + step);
                assertEquals(expected, wal.state().entries(), "step " + step);
            }
        }
    }

    private Path initialized() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("data"));
        try (var wal = FileWal.create(directory)) { wal.persist(FileWalTest.BATCH); }
        return directory;
    }

    private int runChild(Path directory, String boundary) throws Exception {
        var classpath = new ArrayList<String>();
        for (Class<?> type : List.of(WalCrashProcess.class, FileWal.class, HardState.class)) {
            classpath.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        }
        Path output = temporary.resolve(boundary + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", String.join(System.getProperty("path.separator"), classpath), WalCrashProcess.class.getName(),
                directory.toString(), boundary).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Child JVM timed out: " + boundary);
            int result = process.exitValue();
            assertTrue(result == 73 || result == 74, () -> "Unexpected child exit " + result + ": " + readLog(output));
            return result;
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    private static String readLog(Path path) {
        try { return Files.readString(path); } catch (IOException error) { return error.toString(); }
    }
}
