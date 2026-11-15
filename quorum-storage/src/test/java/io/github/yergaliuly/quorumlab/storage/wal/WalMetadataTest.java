package io.github.yergaliuly.quorumlab.storage.wal;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

class WalMetadataTest {
    private static final UUID ID = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");

    @Test void encodesKnownBigEndianMetadataAndRoundTrips() throws Exception {
        byte[] header = WalMetadata.header(ID);
        assertEquals(26, header.length);
        assertEquals(0x51534547, ByteBuffer.wrap(header).getInt());
        assertEquals(ID, WalMetadata.readHeader(header));
        var checkpoint = new WalMetadata.Checkpoint(ID, 7, 1000);
        byte[] bytes = WalMetadata.checkpoint(checkpoint);
        assertEquals(42, bytes.length);
        assertEquals(0x5143484b, ByteBuffer.wrap(bytes).getInt());
        assertEquals(7, ByteBuffer.wrap(bytes).getLong(22));
        assertEquals(1000, ByteBuffer.wrap(bytes).getLong(30));
        assertEquals(checkpoint, WalMetadata.readCheckpoint(bytes));
    }

    @Test void rejectsEverySingleByteCorruptionAndEveryTruncatedPrefix() {
        byte[] header = WalMetadata.header(ID);
        byte[] checkpoint = WalMetadata.checkpoint(new WalMetadata.Checkpoint(ID, 0, 26));
        for (int i = 0; i < header.length; i++) {
            byte[] damaged = header.clone(); damaged[i] ^= 1;
            assertThrows(WalRecoveryException.class, () -> WalMetadata.readHeader(damaged));
            byte[] prefix = Arrays.copyOf(header, i);
            assertThrows(WalRecoveryException.class, () -> WalMetadata.readHeader(prefix));
        }
        for (int i = 0; i < checkpoint.length; i++) {
            byte[] damaged = checkpoint.clone(); damaged[i] ^= 1;
            assertThrows(WalRecoveryException.class, () -> WalMetadata.readCheckpoint(damaged));
            byte[] prefix = Arrays.copyOf(checkpoint, i);
            assertThrows(WalRecoveryException.class, () -> WalMetadata.readCheckpoint(prefix));
        }
        assertThrows(WalRecoveryException.class, () -> WalMetadata.readHeader(Arrays.copyOf(header, 27)));
        assertThrows(WalRecoveryException.class, () -> WalMetadata.readCheckpoint(Arrays.copyOf(checkpoint, 43)));
    }

    @Test void rejectsInvalidFieldsEvenWithValidChecksum() {
        byte[] bytes = WalMetadata.checkpoint(new WalMetadata.Checkpoint(ID, 1, 100));
        for (int position : new int[]{0, 4, 22, 30}) {
            byte[] changed = bytes.clone();
            if (position == 22 || position == 30) ByteBuffer.wrap(changed).putLong(position, -1);
            else changed[position] ^= 1;
            reseal(changed);
            assertThrows(WalRecoveryException.class, () -> WalMetadata.readCheckpoint(changed));
        }
        byte[] header = WalMetadata.header(ID); header[5] = 2; reseal(header);
        assertThrows(WalRecoveryException.class, () -> WalMetadata.readHeader(header));
    }

    static void reseal(byte[] bytes) {
        var crc = new CRC32C(); crc.update(bytes, 0, bytes.length - 4);
        ByteBuffer.wrap(bytes).putInt(bytes.length - 4, (int) crc.getValue());
    }
}
