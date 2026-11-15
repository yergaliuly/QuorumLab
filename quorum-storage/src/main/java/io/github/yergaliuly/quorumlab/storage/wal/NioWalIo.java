package io.github.yergaliuly.quorumlab.storage.wal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static java.nio.file.StandardOpenOption.*;

/** Real local filesystem operations. No fallback when force/atomic replacement is unsupported. */
class NioWalIo implements WalIo {
    private static final String LOCK = "writer.lock";
    private static final Set<Path> JVM_OWNERS = Collections.synchronizedSet(new HashSet<>());
    private final Path directory;

    NioWalIo(Path directory) throws IOException {
        this.directory = directory.toRealPath();
        if (!Files.isDirectory(this.directory) || this.directory.getParent() == null) {
            throw new IOException("WAL needs an existing non-root data directory: " + directory);
        }
    }

    @Override public Closeable lock() throws IOException {
        // POSIX locks may be released by closing ANY descriptor for the same inode in this JVM.
        // Refuse a second opener before opening a channel, not after OverlappingFileLockException.
        if (!JVM_OWNERS.add(directory)) throw new IOException("WAL directory already locked: " + directory);
        FileChannel channel = null;
        try {
            channel = FileChannel.open(directory.resolve(LOCK), CREATE, READ, WRITE, LinkOption.NOFOLLOW_LINKS);
            var lock = channel.tryLock();
            if (lock == null) throw new IOException("WAL directory already locked: " + directory);
            // Closing the channel releases its lock; never unlink the lock file (inode identity matters).
            FileChannel owner = channel;
            return () -> {
                try { owner.close(); }
                finally { if (!owner.isOpen()) JVM_OWNERS.remove(directory); }
            };
        } catch (IOException | RuntimeException failure) {
            if (channel != null) {
                try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            }
            if (channel == null || !channel.isOpen()) JVM_OWNERS.remove(directory);
            if (failure instanceof OverlappingFileLockException) {
                throw new IOException("WAL directory already locked: " + directory, failure);
            }
            throw failure;
        }
    }

    @Override public void requireUninitialized() throws IOException {
        try (var paths = Files.list(directory)) {
            if (paths.anyMatch(path -> !path.getFileName().toString().equals(LOCK))) {
                throw new FileAlreadyExistsException("Refusing to initialize nonempty WAL directory: " + directory);
            }
        }
    }

    @Override public Handle open(String name, boolean createNew) throws IOException {
        var path = directory.resolve(name);
        var channel = createNew
                ? FileChannel.open(path, CREATE_NEW, READ, WRITE, LinkOption.NOFOLLOW_LINKS)
                : FileChannel.open(path, READ, WRITE, LinkOption.NOFOLLOW_LINKS);
        return new ChannelHandle(channel);
    }

    @Override public void replaceCheckpoint() throws IOException {
        Files.move(directory.resolve(FileWal.TEMP), directory.resolve(FileWal.CHECKPOINT),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override public void deleteTemporaryCheckpoint() throws IOException {
        Files.deleteIfExists(directory.resolve(FileWal.TEMP));
    }

    @Override public void syncDirectory() throws IOException { forceDirectory(directory); }
    @Override public void syncParent() throws IOException { forceDirectory(directory.getParent()); }

    private static void forceDirectory(Path directory) throws IOException {
        try (var channel = FileChannel.open(directory, READ)) { channel.force(true); }
    }

    private record ChannelHandle(FileChannel channel) implements Handle {
        @Override public int read(ByteBuffer target, long position) throws IOException { return channel.read(target, position); }
        @Override public int write(ByteBuffer source, long position) throws IOException { return channel.write(source, position); }
        @Override public long size() throws IOException { return channel.size(); }
        @Override public void truncate(long size) throws IOException { channel.truncate(size); }
        @Override public void force() throws IOException { channel.force(true); }
        @Override public void close() throws IOException { channel.close(); }
    }
}
