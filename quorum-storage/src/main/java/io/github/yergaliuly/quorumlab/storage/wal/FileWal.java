package io.github.yergaliuly.quorumlab.storage.wal;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Serialized, synchronous single-segment WAL. Success means WAL + checkpoint + directory were forced.
 * IOException during persist leaves the durable outcome unknown; close and reopen before further use.
 * The data directory and its ancestor hierarchy must already exist. Linux local filesystem contract only.
 */
public final class FileWal implements AutoCloseable {
    static final String SEGMENT = "wal-0000000000000001.log";
    static final String CHECKPOINT = "checkpoint";
    static final String TEMP = "checkpoint.tmp";
    private static final int FRAME_HEADER_BYTES = 11;
    private static final int FRAME_OVERHEAD_BYTES = 15;
    private static final int MAX_ZERO_PROGRESS = 16;
    private final WalIo io;
    private final Closeable lock;
    private final WalIo.Handle segment;
    private final WalCodec codec = new WalCodec();
    private WalMetadata.Checkpoint checkpoint;
    private WalState state;
    private boolean failed;
    private boolean closed;

    private FileWal(WalIo io, Closeable lock, WalIo.Handle segment,
                    WalMetadata.Checkpoint checkpoint, WalState state) {
        this.io = io; this.lock = lock; this.segment = segment; this.checkpoint = checkpoint; this.state = state;
    }

    /** Explicit initialization. Never overwrites or repairs an existing/incompletely initialized store. */
    public static FileWal create(Path directory) throws IOException { return create(new NioWalIo(directory), UUID.randomUUID()); }
    /** Recovery requires existing valid metadata; a missing store is never interpreted as empty. */
    public static FileWal open(Path directory) throws IOException { return open(new NioWalIo(directory)); }

    static FileWal create(WalIo io, UUID id) throws IOException {
        Objects.requireNonNull(id, "id");
        var lock = io.lock();
        WalIo.Handle segment = null;
        try {
            io.requireUninitialized();
            io.syncParent();
            io.syncDirectory();
            segment = io.open(SEGMENT, true);
            writeFully(segment, ByteBuffer.wrap(WalMetadata.header(id)), 0);
            segment.force();
            var checkpoint = new WalMetadata.Checkpoint(id, 0, WalMetadata.HEADER_BYTES);
            publishCheckpoint(io, checkpoint);
            return new FileWal(io, lock, segment, checkpoint, WalState.initial());
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(segment, failure); closeAfterFailure(lock, failure); throw failure;
        }
    }

    static FileWal open(WalIo io) throws IOException {
        var lock = io.lock();
        WalIo.Handle segment = null;
        try {
            WalMetadata.Checkpoint checkpoint;
            try (var metadata = io.open(CHECKPOINT, false)) {
                if (metadata.size() != WalMetadata.CHECKPOINT_BYTES) throw new WalRecoveryException("Wrong checkpoint file length");
                checkpoint = WalMetadata.readCheckpoint(readBytes(metadata, 0, WalMetadata.CHECKPOINT_BYTES));
            }
            segment = io.open(SEGMENT, false);
            if (segment.size() < checkpoint.offset()) throw new WalRecoveryException("WAL shorter than checkpoint offset " + checkpoint.offset());
            UUID id = WalMetadata.readHeader(readBytes(segment, 0, WalMetadata.HEADER_BYTES));
            if (!id.equals(checkpoint.segmentId())) throw new WalRecoveryException("Checkpoint belongs to a different WAL segment");
            WalState state = replay(segment, checkpoint.offset());
            // Do not mutate the store until the entire checkpointed prefix passes framing and history validation.
            if (segment.size() > checkpoint.offset()) segment.truncate(checkpoint.offset());
            segment.force();
            io.deleteTemporaryCheckpoint();
            io.syncDirectory();
            return new FileWal(io, lock, segment, checkpoint, state);
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(segment, failure); closeAfterFailure(lock, failure); throw failure;
        }
    }

    public synchronized WalState state() {
        if (closed || failed) throw new IllegalStateException("WAL closed or failed; reopen for authoritative state");
        return state;
    }

    /** An atomic durability batch, not a Raft commit. Callers still need the Raft quorum/commit rules. */
    public synchronized WalState persist(List<WalRecord> records) throws IOException {
        if (closed || failed) throw new IOException("WAL closed or failed; reopen before persisting");
        List<WalRecord> batch = List.copyOf(records);
        if (batch.isEmpty()) throw new IllegalArgumentException("A persist batch must not be empty");
        WalState nextState = state.apply(batch);
        var encoded = new ArrayList<byte[]>(batch.size());
        long end = checkpoint.offset();
        long generation;
        try {
            generation = Math.addExact(checkpoint.generation(), 1);
            for (WalRecord record : batch) {
                byte[] frame = codec.encode(record);
                encoded.add(frame); end = Math.addExact(end, frame.length);
            }
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("WAL offset/generation exhausted", overflow);
        }
        var nextCheckpoint = new WalMetadata.Checkpoint(checkpoint.segmentId(), generation, end);
        try {
            long position = checkpoint.offset();
            for (byte[] frame : encoded) {
                writeFully(segment, ByteBuffer.wrap(frame), position); position += frame.length;
            }
            segment.force();
            publishCheckpoint(io, nextCheckpoint);
            checkpoint = nextCheckpoint;
            state = nextState;
            return state;
        } catch (IOException | RuntimeException | Error failure) {
            failed = true;
            throw failure;
        }
    }

    private static void publishCheckpoint(WalIo io, WalMetadata.Checkpoint checkpoint) throws IOException {
        try (var temporary = io.open(TEMP, true)) {
            writeFully(temporary, ByteBuffer.wrap(WalMetadata.checkpoint(checkpoint)), 0);
            temporary.force();
        }
        io.replaceCheckpoint();
        io.syncDirectory();
    }

    private static WalState replay(WalIo.Handle segment, long end) throws IOException {
        var builder = new WalState.Builder(WalState.initial());
        var codec = new WalCodec();
        long position = WalMetadata.HEADER_BYTES;
        while (position < end) {
            try {
                if (end - position < FRAME_HEADER_BYTES) throw new WalRecoveryException("Incomplete durable frame header");
                byte[] header = readBytes(segment, position, FRAME_HEADER_BYTES);
                // The existing decoder validates magic/version/type/length before any payload allocation.
                codec.decode(ByteBuffer.wrap(header));
                int length = ByteBuffer.wrap(header).getInt(7) + FRAME_OVERHEAD_BYTES;
                if (length > end - position) throw new WalRecoveryException("Checkpoint cuts a durable frame");
                var frame = ByteBuffer.allocate(length).put(header);
                readFully(segment, frame, position + FRAME_HEADER_BYTES);
                WalRecord record = codec.decode(frame.flip()).orElseThrow(() -> new WalRecoveryException("Incomplete durable frame"));
                builder.apply(record);
                position += length;
            } catch (WalFormatException | IllegalArgumentException | WalRecoveryException failure) {
                throw new WalRecoveryException("Invalid durable WAL record at byte " + position + ": " + failure.getMessage(), failure);
            }
        }
        return builder.snapshot();
    }

    private static byte[] readBytes(WalIo.Handle file, long position, int length) throws IOException {
        var bytes = ByteBuffer.allocate(length); readFully(file, bytes, position); return bytes.array();
    }

    private static void readFully(WalIo.Handle file, ByteBuffer target, long position) throws IOException {
        int stalled = 0;
        while (target.hasRemaining()) {
            int count = file.read(target, position);
            if (count < 0) throw new EOFException("Unexpected EOF at byte " + position);
            if (count == 0) {
                if (++stalled >= MAX_ZERO_PROGRESS) throw new IOException("WAL read made no progress at byte " + position);
            } else { position += count; stalled = 0; }
        }
    }

    private static void writeFully(WalIo.Handle file, ByteBuffer source, long position) throws IOException {
        int stalled = 0;
        while (source.hasRemaining()) {
            int count = file.write(source, position);
            if (count < 0) throw new IOException("Negative WAL write result");
            if (count == 0) {
                if (++stalled >= MAX_ZERO_PROGRESS) throw new IOException("WAL write made no progress at byte " + position);
            } else { position += count; stalled = 0; }
        }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        try { segment.close(); } catch (IOException caught) { failure = caught; }
        try { lock.close(); } catch (IOException caught) {
            if (failure == null) failure = caught; else failure.addSuppressed(caught);
        }
        if (failure != null) throw failure;
    }

    private static void closeAfterFailure(Closeable resource, Throwable failure) {
        if (resource != null) {
            try { resource.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
        }
    }
}
