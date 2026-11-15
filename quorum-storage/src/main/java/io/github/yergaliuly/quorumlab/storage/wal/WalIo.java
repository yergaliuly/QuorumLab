package io.github.yergaliuly.quorumlab.storage.wal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Package-private seam for deterministic disk failures, not an alternative public durability mode. */
interface WalIo {
    Closeable lock() throws IOException;
    void requireUninitialized() throws IOException;
    Handle open(String name, boolean createNew) throws IOException;
    void replaceCheckpoint() throws IOException;
    void deleteTemporaryCheckpoint() throws IOException;
    void syncDirectory() throws IOException;
    void syncParent() throws IOException;

    interface Handle extends Closeable {
        int read(ByteBuffer target, long position) throws IOException;
        int write(ByteBuffer source, long position) throws IOException;
        long size() throws IOException;
        void truncate(long size) throws IOException;
        void force() throws IOException;
    }
}
