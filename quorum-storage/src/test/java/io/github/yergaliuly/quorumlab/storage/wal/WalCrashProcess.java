package io.github.yergaliuly.quorumlab.storage.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Separate JVM fixture. halt deliberately skips finally/close/shutdown hooks. Never shipped in main. */
public final class WalCrashProcess {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        if (args[1].equals("probeLock")) {
            try (var ignored = FileWal.open(directory)) { System.exit(0); }
            catch (IOException locked) { System.err.println(locked); System.exit(74); }
            return;
        }
        var io = new CrashIo(directory, args[1]);
        var wal = FileWal.open(io);
        io.armed = true;
        wal.persist(List.of(new WalRecord.SetHardState(2, Optional.empty()),
                new WalRecord.AppendEntry(2, 2, WalRecord.EntryType.COMMAND, new byte[]{2}),
                new WalRecord.SetCommitIndex(2)));
        io.hit("afterAck");
        throw new AssertionError("Crash boundary was not visited: " + args[1]);
    }

    private static final class CrashIo extends NioWalIo {
        private final String boundary;
        boolean armed;
        CrashIo(Path directory, String boundary) throws IOException { super(directory); this.boundary = boundary; }
        void hit(String point) { if (armed && point.equals(boundary)) Runtime.getRuntime().halt(73); }

        @Override public Handle open(String name, boolean createNew) throws IOException {
            var delegate = super.open(name, createNew);
            boolean wal = name.equals(FileWal.SEGMENT);
            String kind = wal ? "Wal" : "Checkpoint";
            return new Handle() {
                @Override public int read(ByteBuffer target, long position) throws IOException { return delegate.read(target, position); }
                @Override public int write(ByteBuffer source, long position) throws IOException {
                    hit("before" + kind + "Write");
                    if (armed && boundary.equals("partial" + kind + "Write")) {
                        var partial = source.duplicate(); partial.limit(partial.position() + Math.min(5, partial.remaining()));
                        delegate.write(partial, position); hit("partial" + kind + "Write");
                    }
                    int count = delegate.write(source, position); hit("after" + kind + "Write"); return count;
                }
                @Override public long size() throws IOException { return delegate.size(); }
                @Override public void truncate(long size) throws IOException { delegate.truncate(size); }
                @Override public void force() throws IOException {
                    hit("before" + kind + "Force"); delegate.force(); hit("after" + kind + "Force");
                }
                @Override public void close() throws IOException { delegate.close(); }
            };
        }
        @Override public void replaceCheckpoint() throws IOException { hit("beforeRename"); super.replaceCheckpoint(); hit("afterRename"); }
        @Override public void syncDirectory() throws IOException { hit("beforeDirectoryForce"); super.syncDirectory(); hit("afterDirectoryForce"); }
    }
}
