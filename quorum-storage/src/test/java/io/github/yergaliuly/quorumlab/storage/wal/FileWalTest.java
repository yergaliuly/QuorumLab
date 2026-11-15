package io.github.yergaliuly.quorumlab.storage.wal;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.yergaliuly.quorumlab.storage.wal.WalStateTest.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class FileWalTest {
    static final UUID ID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    static final List<WalRecord> BATCH = List.of(new WalRecord.SetHardState(1, Optional.of(A)),
            entry(1, 1), new WalRecord.SetCommitIndex(1));

    @Test void persistsWholeBatchAndRecoversAfterLossOfVolatileState() throws Exception {
        var disk = new ModelDisk();
        try (var wal = FileWal.create(disk, ID)) {
            assertEquals(0, wal.state().hardState().currentTerm());
            wal.persist(BATCH);
            assertEquals(1, wal.state().hardState().commitIndex());
        }
        disk.crash(false);
        try (var wal = FileWal.open(disk)) {
            assertEquals(Optional.of(A), wal.state().hardState().votedFor());
            assertEquals(List.of(entry(1, 1)), wal.state().entries());
            wal.persist(List.of(term(2), entry(2, 2)));
        }
        try (var wal = FileWal.open(disk)) {
            assertEquals(2, wal.state().entries().size());
            assertEquals(1, wal.state().hardState().commitIndex());
        }
        assertEquals(0, disk.handles);
        assertFalse(disk.locked);
    }

    @Test void publishesStateOnlyAfterAllDurabilityBarriers() throws Exception {
        var disk = new ModelDisk();
        try (var wal = FileWal.create(disk, ID)) {
            disk.trace.clear();
            disk.observer = ignored -> assertEquals(0, wal.state().hardState().currentTerm());
            wal.persist(BATCH);
            assertEquals(List.of("write:" + FileWal.SEGMENT, "write:" + FileWal.SEGMENT,
                    "write:" + FileWal.SEGMENT, "force:" + FileWal.SEGMENT, "write:" + FileWal.TEMP,
                    "force:" + FileWal.TEMP, "rename", "directoryForce"), disk.trace);
            var checkpoint = WalMetadata.readCheckpoint(disk.bytes(FileWal.CHECKPOINT));
            assertEquals(1, checkpoint.generation());
            assertEquals(disk.bytes(FileWal.SEGMENT).length, checkpoint.offset());
        }
    }

    @Test void loopsOverShortWritesAndShortReads() throws Exception {
        var disk = new ModelDisk(); disk.maxWrite = 2; disk.maxRead = 3;
        try (var wal = FileWal.create(disk, ID)) { wal.persist(BATCH); }
        disk.crash(false);
        try (var wal = FileWal.open(disk)) { assertEquals(1, wal.state().hardState().commitIndex()); }
    }

    static Stream<Arguments> faults() {
        return Stream.of("write:" + FileWal.SEGMENT, "force:" + FileWal.SEGMENT,
                "write:" + FileWal.TEMP, "force:" + FileWal.TEMP, "rename", "directoryForce")
                .flatMap(operation -> Stream.of(Arguments.of(operation, false), Arguments.of(operation, true)));
    }
    @ParameterizedTest @MethodSource("faults")
    void failurePoisonsWriterAndRecoveryNeverExposesPartialBatch(String operation, boolean after) throws Exception {
        var disk = new ModelDisk();
        try (var wal = FileWal.create(disk, ID)) {
            disk.fail(operation, 1, after);
            assertThrows(IOException.class, () -> wal.persist(BATCH));
            int calls = disk.trace.size();
            assertThrows(IOException.class, () -> wal.persist(BATCH));
            assertThrows(IllegalStateException.class, wal::state);
            assertEquals(calls, disk.trace.size());
        }
        disk.crash(false);
        boolean durable = operation.equals("directoryForce") && after;
        try (var wal = FileWal.open(disk)) {
            assertEquals(durable ? 1 : 0, wal.state().hardState().currentTerm());
            assertEquals(durable ? 1 : 0, wal.state().entries().size());
            assertEquals(durable ? 1 : 0, wal.state().hardState().commitIndex());
        }
        assertEquals(0, disk.handles);
    }

    @Test void renameMaySurviveWithoutAcknowledgementAndRecoveryMakesItDurable() throws Exception {
        var disk = new ModelDisk();
        try (var wal = FileWal.create(disk, ID)) {
            disk.fail("directoryForce", 1, false);
            assertThrows(IOException.class, () -> wal.persist(BATCH));
        }
        disk.crash(true);
        try (var wal = FileWal.open(disk)) { assertEquals(1, wal.state().hardState().commitIndex()); }
        disk.crash(false);
        try (var wal = FileWal.open(disk)) { assertEquals(1, wal.state().hardState().commitIndex()); }
    }

    @Test void rejectsInvalidBatchBeforeAnyIoAndWriterRemainsUsable() throws Exception {
        var disk = new ModelDisk();
        try (var wal = FileWal.create(disk, ID)) {
            disk.trace.clear();
            assertThrows(IllegalArgumentException.class, () -> wal.persist(List.of(term(1), entry(2, 1))));
            assertThrows(IllegalArgumentException.class, () -> wal.persist(List.of()));
            assertTrue(disk.trace.isEmpty());
            wal.persist(BATCH);
        }
    }

    @Test void zeroProgressWriteFailsWithoutHanging() throws Exception {
        var disk = new ModelDisk();
        try (var wal = FileWal.create(disk, ID)) {
            disk.maxWrite = 0;
            assertThrows(IOException.class, () -> wal.persist(BATCH));
            assertThrows(IllegalStateException.class, wal::state);
        }
    }

    @Test void zeroProgressReadFailsAndReleasesResources() throws Exception {
        var disk = initialized(); disk.maxRead = 0;
        assertThrows(IOException.class, () -> FileWal.open(disk));
        assertEquals(0, disk.handles); assertFalse(disk.locked);
    }

    @ParameterizedTest @ValueSource(ints = {1, 9, 27, 60})
    void discardsOnlySuffixBeyondCheckpoint(int tailLength) throws Exception {
        var disk = initialized();
        byte[] before = disk.bytes(FileWal.SEGMENT);
        byte[] tail = Arrays.copyOf(before, before.length + tailLength);
        Arrays.fill(tail, before.length, tail.length, (byte) 0x7f);
        disk.corrupt(FileWal.SEGMENT, tail);
        try (var wal = FileWal.open(disk)) { assertEquals(1, wal.state().hardState().commitIndex()); }
        assertArrayEquals(before, disk.bytes(FileWal.SEGMENT));
        disk.crash(false);
        assertArrayEquals(before, disk.bytes(FileWal.SEGMENT));
    }

    @Test void completeUncheckpointedRecordAndStaleTempAreNotReplayed() throws Exception {
        var disk = initialized();
        try (var wal = FileWal.open(disk)) {
            disk.fail("rename", 1, false);
            assertThrows(IOException.class, () -> wal.persist(List.of(term(2))));
        }
        // Process restart, without pretending that close flushed or discarded the page cache.
        try (var wal = FileWal.open(disk)) { assertEquals(1, wal.state().hardState().currentTerm()); }
        assertThrows(java.nio.file.NoSuchFileException.class, () -> disk.open(FileWal.TEMP, false));
    }

    @ParameterizedTest @ValueSource(ints = {26, 40, -1})
    void corruptionAnywhereInDurablePrefixFailsClosed(int offset) throws Exception {
        var disk = initialized();
        byte[] bytes = disk.bytes(FileWal.SEGMENT);
        bytes[offset < 0 ? bytes.length - 1 : offset] ^= 1;
        disk.corrupt(FileWal.SEGMENT, bytes);
        assertThrows(WalRecoveryException.class, () -> FileWal.open(disk));
        assertArrayEquals(bytes, disk.bytes(FileWal.SEGMENT));
        assertFalse(disk.locked); assertEquals(0, disk.handles);
    }

    @Test void missingByteInsideCheckpointIsCorruptionNotDiscardableTail() throws Exception {
        var disk = initialized(); byte[] bytes = disk.bytes(FileWal.SEGMENT);
        disk.corrupt(FileWal.SEGMENT, Arrays.copyOf(bytes, bytes.length - 1));
        assertThrows(WalRecoveryException.class, () -> FileWal.open(disk));
    }

    @ParameterizedTest @ValueSource(strings = {"missingCheckpoint", "missingSegment", "badCheckpoint",
            "wrongSegment", "futureOffset", "midRecord", "negativeLength", "oversizedLength", "invalidHistory"})
    void refusesUntrustworthyRecovery(String scenario) throws Exception {
        var disk = initialized();
        var old = WalMetadata.readCheckpoint(disk.bytes(FileWal.CHECKPOINT));
        switch (scenario) {
            case "missingCheckpoint" -> disk.lose(FileWal.CHECKPOINT);
            case "missingSegment" -> disk.lose(FileWal.SEGMENT);
            case "badCheckpoint" -> { byte[] bytes = disk.bytes(FileWal.CHECKPOINT); bytes[15] ^= 1; disk.corrupt(FileWal.CHECKPOINT, bytes); }
            case "wrongSegment" -> disk.corrupt(FileWal.SEGMENT, replaceHeader(disk.bytes(FileWal.SEGMENT), UUID.randomUUID()));
            case "futureOffset", "midRecord" -> disk.corrupt(FileWal.CHECKPOINT, WalMetadata.checkpoint(
                    new WalMetadata.Checkpoint(ID, 1, old.offset() + (scenario.equals("futureOffset") ? 1 : -1))));
            case "negativeLength", "oversizedLength" -> {
                byte[] bytes = disk.bytes(FileWal.SEGMENT);
                ByteBuffer.wrap(bytes).putInt(26 + 7, scenario.equals("negativeLength") ? -1 : Integer.MAX_VALUE);
                disk.corrupt(FileWal.SEGMENT, bytes);
            }
            case "invalidHistory" -> {
                byte[] invalid = new WalCodec().encode(new WalRecord.SetCommitIndex(9));
                byte[] bytes = Arrays.copyOf(disk.bytes(FileWal.SEGMENT), (int) old.offset() + invalid.length);
                System.arraycopy(invalid, 0, bytes, (int) old.offset(), invalid.length);
                disk.corrupt(FileWal.SEGMENT, bytes);
                disk.corrupt(FileWal.CHECKPOINT, WalMetadata.checkpoint(new WalMetadata.Checkpoint(ID, 2, bytes.length)));
            }
        }
        assertThrows(IOException.class, () -> FileWal.open(disk));
        assertEquals(0, disk.handles); assertFalse(disk.locked);
    }

    @Test void excludesSecondWriterAndNeverReinitializesExistingStore() throws Exception {
        var disk = initialized();
        try (var wal = FileWal.open(disk)) { assertThrows(IOException.class, () -> FileWal.open(disk)); }
        assertThrows(IOException.class, () -> FileWal.create(disk, UUID.randomUUID()));
        try (var wal = FileWal.open(disk)) { assertEquals(1, wal.state().hardState().commitIndex()); }
    }

    @Test void initializationFailureReleasesLockAndDoesNotInventAnEmptyStore() throws Exception {
        var disk = new ModelDisk(); disk.fail("rename", 1, false);
        assertThrows(IOException.class, () -> FileWal.create(disk, ID));
        assertThrows(IOException.class, () -> FileWal.open(disk));
        assertFalse(disk.locked); assertEquals(0, disk.handles);
    }

    @Test void closedHandleRejectsFurtherUse() throws Exception {
        var disk = new ModelDisk(); var wal = FileWal.create(disk, ID); wal.close(); wal.close();
        assertThrows(IOException.class, () -> wal.persist(BATCH));
        assertThrows(IllegalStateException.class, wal::state);
    }

    private static ModelDisk initialized() throws Exception {
        var disk = new ModelDisk();
        try (var wal = FileWal.create(disk, ID)) { wal.persist(BATCH); }
        return disk;
    }
    private static byte[] replaceHeader(byte[] bytes, UUID id) {
        System.arraycopy(WalMetadata.header(id), 0, bytes, 0, 26); return bytes;
    }
}
