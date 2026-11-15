package io.github.yergaliuly.quorumlab.storage.wal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.function.Consumer;

/** Independent disk model: force persists inode bytes; directory sync persists name-to-inode bindings. */
final class ModelDisk implements WalIo {
    private static final class Inode {
        byte[] bytes = new byte[0];
        byte[] forced = new byte[0];
    }
    private Map<String, Inode> names = new HashMap<>();
    private Map<String, Inode> durableNames = new HashMap<>();
    final List<String> trace = new ArrayList<>();
    int maxWrite = Integer.MAX_VALUE;
    int maxRead = Integer.MAX_VALUE;
    boolean locked;
    int handles;
    Consumer<String> observer = ignored -> {};
    private String failure;
    private boolean after;
    private int remaining;

    void fail(String operation, int occurrence, boolean after) {
        failure = operation; remaining = occurrence; this.after = after;
    }
    private void before(String operation) throws IOException {
        trace.add(operation); observer.accept(operation);
        if (!after) failIfArmed(operation);
    }
    private void after(String operation) throws IOException {
        if (after) failIfArmed(operation);
    }
    private void failIfArmed(String operation) throws IOException {
        if (operation.equals(failure) && --remaining == 0) {
            failure = null;
            throw new IOException("Injected ENOSPC/EIO at " + operation);
        }
    }

    void crash(boolean retainUnsyncedRename) {
        if (retainUnsyncedRename) durableNames = new HashMap<>(names);
        names = new HashMap<>(durableNames);
        for (Inode inode : names.values()) inode.bytes = inode.forced.clone();
        failure = null; observer = ignored -> {}; locked = false;
    }
    byte[] bytes(String name) { return names.get(name).bytes.clone(); }
    void corrupt(String name, byte[] bytes) {
        Inode inode = names.get(name); inode.bytes = bytes.clone(); inode.forced = bytes.clone();
    }
    void lose(String name) { names.remove(name); durableNames.remove(name); }

    @Override public Closeable lock() throws IOException {
        if (locked) throw new IOException("Writer already locked");
        locked = true;
        return () -> locked = false;
    }

    @Override public void requireUninitialized() throws IOException {
        if (!names.isEmpty()) throw new FileAlreadyExistsException("Store is not empty");
    }

    @Override public Handle open(String name, boolean createNew) throws IOException {
        if (createNew) {
            if (names.containsKey(name)) throw new FileAlreadyExistsException(name);
            names.put(name, new Inode());
        } else if (!names.containsKey(name)) throw new NoSuchFileException(name);
        Inode inode = names.get(name); handles++;
        return new Handle() {
            private boolean closed;
            @Override public int read(ByteBuffer target, long position) throws IOException {
                before("read:" + name);
                if (position >= inode.bytes.length) return -1;
                int count = Math.min(maxRead, Math.min(target.remaining(), inode.bytes.length - Math.toIntExact(position)));
                target.put(inode.bytes, Math.toIntExact(position), count);
                after("read:" + name); return count;
            }
            @Override public int write(ByteBuffer source, long position) throws IOException {
                before("write:" + name);
                int count = Math.min(maxWrite, source.remaining());
                int end = Math.toIntExact(position + count);
                if (end > inode.bytes.length) inode.bytes = Arrays.copyOf(inode.bytes, end);
                source.get(inode.bytes, Math.toIntExact(position), count);
                after("write:" + name); return count;
            }
            @Override public long size() { return inode.bytes.length; }
            @Override public void truncate(long size) throws IOException {
                before("truncate:" + name);
                if (size < inode.bytes.length) inode.bytes = Arrays.copyOf(inode.bytes, Math.toIntExact(size));
                after("truncate:" + name);
            }
            @Override public void force() throws IOException {
                before("force:" + name); inode.forced = inode.bytes.clone(); after("force:" + name);
            }
            @Override public void close() { if (!closed) { handles--; closed = true; } }
        };
    }

    @Override public void replaceCheckpoint() throws IOException {
        before("rename");
        names.put(FileWal.CHECKPOINT, names.remove(FileWal.TEMP));
        after("rename");
    }
    @Override public void deleteTemporaryCheckpoint() throws IOException {
        before("deleteTemp"); names.remove(FileWal.TEMP); after("deleteTemp");
    }
    @Override public void syncDirectory() throws IOException {
        before("directoryForce"); durableNames = new HashMap<>(names); after("directoryForce");
    }
    @Override public void syncParent() throws IOException { before("parentForce"); after("parentForce"); }
}
