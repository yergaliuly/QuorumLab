package io.github.yergaliuly.quorumlab.storage.wal;

import static io.github.yergaliuly.quorumlab.storage.wal.WalFormatException.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.yergaliuly.quorumlab.core.NodeId;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class WalCodecTest {
    private final WalCodec codec = new WalCodec();
    private static final String COMMIT_SEVEN = "5157414c0001040000000800000000000000077df97d6b";

    // Layout bytes were written from the format spec; CRCs came from an independent bitwise
    // Castagnoli calculation (reflected polynomial 0x82f63b78), not this codec or JDK CRC32C.
    static Stream<Arguments> goldenVectors() {
        return Stream.of(
                Arguments.of(new WalRecord.SetHardState(0, Optional.empty()),
                        "5157414c0001010000000c0000000000000000ffffffff086e09c7"),
                Arguments.of(new WalRecord.SetHardState(2, Optional.of(new NodeId("n1"))),
                        "5157414c0001010000000e0000000000000002000000026e31b9045e1c"),
                Arguments.of(new WalRecord.AppendEntry(3, 2, WalRecord.EntryType.COMMAND, hex("00ff7f")),
                        "5157414c0001020000001800000000000000030000000000000002010000000300ff7f1c1d1b13"),
                Arguments.of(new WalRecord.AppendEntry(3, 2, WalRecord.EntryType.NO_OP, new byte[0]),
                        "5157414c000102000000150000000000000003000000000000000200000000006abaa768"),
                Arguments.of(new WalRecord.TruncateSuffix(4),
                        "5157414c000103000000080000000000000004fc9727a4"),
                Arguments.of(new WalRecord.SetCommitIndex(7), COMMIT_SEVEN));
    }

    @ParameterizedTest
    @MethodSource("goldenVectors")
    void matchesIndependentWireVectors(WalRecord record, String expectedHex) throws Exception {
        assertArrayEquals(hex(expectedHex), codec.encode(record));
        var source = ByteBuffer.wrap(hex(expectedHex));
        assertEquals(record, codec.decode(source).orElseThrow());
        assertFalse(source.hasRemaining());
    }

    @ParameterizedTest
    @MethodSource("goldenVectors")
    void everyIncompletePrefixLeavesPositionUnchanged(WalRecord ignored, String frameHex) throws Exception {
        byte[] frame = hex(frameHex);
        for (int size = 0; size < frame.length; size++) {
            var source = ByteBuffer.allocate(frame.length + 3).put(new byte[3]).put(frame);
            source.position(3).limit(3 + size).mark();
            assertTrue(codec.decode(source).isEmpty(), "prefix bytes=" + size);
            assertEquals(3, source.position());
            assertEquals(3 + size, source.limit());
            assertEquals(3, source.reset().position());
        }
    }

    @ParameterizedTest
    @MethodSource("goldenVectors")
    void rejectsTrailingPayloadEvenWithValidChecksum(WalRecord ignored, String frameHex) {
        byte[] frame = hex(frameHex);
        byte[] payload = Arrays.copyOfRange(frame, 11, frame.length - 4);
        byte[] payloadWithExtraByte = Arrays.copyOf(payload, payload.length + 1);
        expectFormat(INVALID_PAYLOAD, rawFrame(frame[6], payloadWithExtraByte));
    }

    @ParameterizedTest
    @ValueSource(strings = {"heap", "direct", "readOnly", "slice"})
    void supportsBufferViewsAndIgnoresCallerByteOrder(String kind) throws Exception {
        byte[] frame = hex(COMMIT_SEVEN);
        ByteBuffer source = kind.equals("direct")
                ? ByteBuffer.allocateDirect(frame.length + 8) : ByteBuffer.allocate(frame.length + 8);
        if (kind.equals("slice")) {
            source.position(2);
            source = source.slice();
        }
        source.position(3).put(frame).put((byte) 99);
        source.position(3).limit(3 + frame.length);
        if (kind.equals("readOnly")) {
            source = source.asReadOnlyBuffer();
        }
        source.order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(new WalRecord.SetCommitIndex(7), codec.decode(source).orElseThrow());
        assertEquals(3 + frame.length, source.position());
        assertEquals(3 + frame.length, source.limit());
        assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
        assertTrue(codec.decode(source).isEmpty());
    }

    @Test
    void readsOneFrameAtATimeAndCanResumeAfterMoreBytesArrive() throws Exception {
        byte[] frame = hex(COMMIT_SEVEN);
        var source = ByteBuffer.allocate(frame.length * 3).put(frame).put(frame).put(frame).flip();
        source.limit(frame.length * 2 + 7);
        for (int i = 1; i <= 2; i++) {
            assertEquals(new WalRecord.SetCommitIndex(7), codec.decode(source).orElseThrow());
            assertEquals(i * frame.length, source.position());
        }
        assertTrue(codec.decode(source).isEmpty());
        assertEquals(2 * frame.length, source.position());
        source.limit(source.capacity());
        assertEquals(new WalRecord.SetCommitIndex(7), codec.decode(source).orElseThrow());
        assertFalse(source.hasRemaining());
    }

    @ParameterizedTest
    @MethodSource("invalidHeaders")
    void rejectsBadHeadersBeforeAllocatingDeclaredPayload(byte[] header, WalFormatException.Reason reason) {
        expectFormat(reason, header);
    }

    static Stream<Arguments> invalidHeaders() {
        return Stream.of(
                Arguments.of(header(0, 1, 4, 8), BAD_MAGIC),
                Arguments.of(header(0x5157414c, 2, 4, 8), UNSUPPORTED_VERSION),
                Arguments.of(header(0x5157414c, 65535, 4, 8), UNSUPPORTED_VERSION),
                Arguments.of(header(0x5157414c, 1, 255, 8), UNKNOWN_RECORD_TYPE),
                Arguments.of(header(0x5157414c, 1, 4, -1), INVALID_LENGTH),
                Arguments.of(header(0x5157414c, 1, 4, WalCodec.MAX_PAYLOAD_BYTES + 1), INVALID_LENGTH),
                Arguments.of(header(0x5157414c, 1, 4, Integer.MAX_VALUE), INVALID_LENGTH));
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 11, 22})
    void checksumCoversHeaderPayloadAndChecksumBytes(int offset) {
        byte[] frame = hex(COMMIT_SEVEN);
        // Both types 3 and 4 allow a positive eight-byte index; only CRC can catch this change.
        frame[offset] = offset == 6 ? (byte) 3 : (byte) (frame[offset] ^ 1);
        expectFormat(CHECKSUM_MISMATCH, frame);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void completeFrameWithMissingTypedFieldsIsCorruption(int type) {
        expectFormat(INVALID_PAYLOAD, rawFrame(type, new byte[0]));
    }

    @ParameterizedTest
    @MethodSource("invalidPayloads")
    void rejectsImpossiblePayloadWithCorrectChecksum(int type, byte[] payload) {
        expectFormat(INVALID_PAYLOAD, rawFrame(type, payload));
    }

    static Stream<Arguments> invalidPayloads() {
        return Stream.of(
                Arguments.of(1, hardPayload(-1, -1, new byte[0])),
                Arguments.of(1, hardPayload(0, 2, hex("6e31"))),
                Arguments.of(1, hardPayload(1, -2, new byte[0])),
                Arguments.of(1, hardPayload(1, 0, new byte[0])),
                Arguments.of(1, hardPayload(1, 2, hex("c328"))),
                Arguments.of(1, hardPayload(1, 3, hex("6e31"))),
                Arguments.of(1, hardPayload(1, 2, hex("2020"))),
                Arguments.of(2, appendPayload(0, 1, 1, 0, new byte[0])),
                Arguments.of(2, appendPayload(1, 0, 1, 0, new byte[0])),
                Arguments.of(2, appendPayload(1, 1, 255, 0, new byte[0])),
                Arguments.of(2, appendPayload(1, 1, 0, 1, new byte[] {1})),
                Arguments.of(2, appendPayload(1, 1, 1, -1, new byte[0])),
                Arguments.of(2, appendPayload(1, 1, 1, Integer.MAX_VALUE, new byte[0])),
                Arguments.of(3, ByteBuffer.allocate(8).putLong(0).array()),
                Arguments.of(4, ByteBuffer.allocate(8).putLong(-1).array()));
    }

    @Test
    void strictUnicodeAndLongCountersRoundtrip() throws Exception {
        var record = new WalRecord.SetHardState(Long.MAX_VALUE, Optional.of(new NodeId("узел-\uD83D\uDE80")));
        assertEquals(record, codec.decode(ByteBuffer.wrap(codec.encode(record))).orElseThrow());
        var entry = new WalRecord.AppendEntry(Long.MAX_VALUE, Long.MAX_VALUE,
                WalRecord.EntryType.COMMAND, new byte[0]);
        assertEquals(entry, codec.decode(ByteBuffer.wrap(codec.encode(entry))).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> codec.encode(
                new WalRecord.SetHardState(1, Optional.of(new NodeId("\uD800")))));
    }

    @Test
    void rejectsOversizedVoteBeforeCreatingAFrame() {
        for (String vote : new String[] {"a".repeat(WalCodec.MAX_PAYLOAD_BYTES),
                "界".repeat(WalCodec.MAX_PAYLOAD_BYTES / 3 + 1)}) {
            assertThrows(IllegalArgumentException.class, () -> codec.encode(
                    new WalRecord.SetHardState(1, Optional.of(new NodeId(vote)))));
        }
    }

    @Test
    void acceptsLargestCommand() throws Exception {
        byte[] command = new byte[WalCodec.MAX_COMMAND_BYTES];
        Arrays.fill(command, (byte) 171);
        var record = new WalRecord.AppendEntry(1, 1, WalRecord.EntryType.COMMAND, command);
        byte[] frame = codec.encode(record);
        assertEquals(WalCodec.MAX_PAYLOAD_BYTES + 15, frame.length);
        assertEquals(record, codec.decode(ByteBuffer.wrap(frame)).orElseThrow());
    }

    @Test
    void generatedRoundtripsAreReproducible() throws Exception {
        var random = new Random(7721);
        for (int i = 0; i < 200; i++) {
            long number = 1L + random.nextInt(1_000_000);
            byte[] command = new byte[random.nextInt(129)];
            random.nextBytes(command);
            WalRecord record = switch (i % 4) {
                case 0 -> new WalRecord.SetHardState(number,
                        random.nextBoolean() ? Optional.empty() : Optional.of(new NodeId("node-" + i)));
                case 1 -> new WalRecord.AppendEntry(number, number, WalRecord.EntryType.COMMAND, command);
                case 2 -> new WalRecord.TruncateSuffix(number);
                default -> new WalRecord.SetCommitIndex(number);
            };
            assertEquals(record, codec.decode(ByteBuffer.wrap(codec.encode(record))).orElseThrow(), "case=" + i);
        }
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> codec.encode(null));
        assertThrows(NullPointerException.class, () -> codec.decode(null));
    }

    private void expectFormat(WalFormatException.Reason reason, byte[] frame) {
        var source = ByteBuffer.allocate(frame.length + 3).put(new byte[3]).put(frame).flip();
        source.position(3).mark();
        byte[] before = source.array().clone();
        var failure = assertThrows(WalFormatException.class, () -> codec.decode(source));
        assertEquals(reason, failure.reason());
        assertEquals(3, source.position());
        assertEquals(frame.length + 3, source.limit());
        assertEquals(3, source.reset().position());
        assertArrayEquals(before, source.array());
    }

    private static byte[] header(int magic, int version, int type, int length) {
        return ByteBuffer.allocate(11).putInt(magic).putShort((short) version)
                .put((byte) type).putInt(length).array();
    }

    private static byte[] rawFrame(int type, byte[] payload) {
        var buffer = ByteBuffer.allocate(15 + payload.length)
                .put(header(0x5157414c, 1, type, payload.length)).put(payload);
        var checksum = new CRC32C();
        checksum.update(buffer.array(), 0, buffer.position());
        return buffer.putInt((int) checksum.getValue()).array();
    }

    private static byte[] hardPayload(long term, int length, byte[] vote) {
        return ByteBuffer.allocate(12 + vote.length).putLong(term).putInt(length).put(vote).array();
    }

    private static byte[] appendPayload(long index, long term, int type, int length, byte[] command) {
        return ByteBuffer.allocate(21 + command.length).putLong(index).putLong(term)
                .put((byte) type).putInt(length).put(command).array();
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
