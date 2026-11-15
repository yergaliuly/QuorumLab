package io.github.yergaliuly.quorumlab.storage.wal;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32C;

final class WalMetadata {
    static final int HEADER_BYTES = 26;
    static final int CHECKPOINT_BYTES = 42;
    private static final int SEGMENT_MAGIC = 0x51534547;
    private static final int CHECKPOINT_MAGIC = 0x5143484b;
    private static final short VERSION = 1;

    record Checkpoint(UUID segmentId, long generation, long offset) {
        Checkpoint {
            Objects.requireNonNull(segmentId, "segmentId");
            if (generation < 0 || offset < HEADER_BYTES || (generation == 0) != (offset == HEADER_BYTES)) {
                throw new IllegalArgumentException("Invalid checkpoint generation/offset");
            }
        }
    }

    static byte[] header(UUID id) {
        return seal(prefix(HEADER_BYTES, SEGMENT_MAGIC, id));
    }

    static UUID readHeader(byte[] bytes) throws WalRecoveryException {
        return readId(verify(bytes, HEADER_BYTES, SEGMENT_MAGIC, "segment header"));
    }

    static byte[] checkpoint(Checkpoint checkpoint) {
        return seal(prefix(CHECKPOINT_BYTES, CHECKPOINT_MAGIC, checkpoint.segmentId())
                .putLong(checkpoint.generation()).putLong(checkpoint.offset()));
    }

    static Checkpoint readCheckpoint(byte[] bytes) throws WalRecoveryException {
        var input = verify(bytes, CHECKPOINT_BYTES, CHECKPOINT_MAGIC, "checkpoint");
        try {
            return new Checkpoint(readId(input), input.getLong(), input.getLong());
        } catch (IllegalArgumentException failure) {
            throw new WalRecoveryException("Invalid checkpoint fields", failure);
        }
    }

    private static ByteBuffer prefix(int size, int magic, UUID id) {
        return ByteBuffer.allocate(size).putInt(magic).putShort(VERSION)
                .putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
    }

    private static UUID readId(ByteBuffer input) { return new UUID(input.getLong(), input.getLong()); }

    private static byte[] seal(ByteBuffer output) {
        var crc = new CRC32C(); crc.update(output.duplicate().flip());
        output.putInt((int) crc.getValue());
        return output.array();
    }

    private static ByteBuffer verify(byte[] bytes, int size, int magic, String name) throws WalRecoveryException {
        if (bytes.length != size) throw new WalRecoveryException("Wrong " + name + " length: " + bytes.length);
        var input = ByteBuffer.wrap(bytes);
        if (input.getInt() != magic) throw new WalRecoveryException("Wrong " + name + " magic");
        if (input.getShort() != VERSION) throw new WalRecoveryException("Unsupported " + name + " version");
        var crc = new CRC32C(); crc.update(bytes, 0, size - 4);
        if ((int) crc.getValue() != input.getInt(size - 4)) throw new WalRecoveryException(name + " CRC32C mismatch");
        return input;
    }
}
