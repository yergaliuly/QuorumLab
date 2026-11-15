package io.github.yergaliuly.quorumlab.storage.wal;

import static io.github.yergaliuly.quorumlab.storage.wal.WalFormatException.Reason.*;

import io.github.yergaliuly.quorumlab.core.NodeId;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.CRC32C;

/** Stateless in-memory codec. Incomplete input is not a decision to discard a WAL tail. */
public final class WalCodec {
    public static final int MAX_PAYLOAD_BYTES = 1_048_576;
    public static final int MAX_COMMAND_BYTES = io.github.yergaliuly.quorumlab.core.LogEntry.MAX_COMMAND_BYTES;
    private static final int MAGIC = 0x5157414c;
    private static final int VERSION = 1;
    private static final int HEADER_BYTES = 11;
    private static final int CHECKSUM_BYTES = 4;
    private static final int SET_HARD_STATE = 1;
    private static final int APPEND_ENTRY = 2;
    private static final int TRUNCATE_SUFFIX = 3;
    private static final int SET_COMMIT_INDEX = 4;

    public byte[] encode(WalRecord record) {
        Objects.requireNonNull(record, "record");
        ByteBuffer payload = encodePayload(record).flip();
        int type = switch (record) {
            case WalRecord.SetHardState ignored -> SET_HARD_STATE;
            case WalRecord.AppendEntry ignored -> APPEND_ENTRY;
            case WalRecord.TruncateSuffix ignored -> TRUNCATE_SUFFIX;
            case WalRecord.SetCommitIndex ignored -> SET_COMMIT_INDEX;
        };
        var frame = ByteBuffer.allocate(HEADER_BYTES + payload.remaining() + CHECKSUM_BYTES)
                .order(ByteOrder.BIG_ENDIAN);
        frame.putInt(MAGIC).putShort((short) VERSION).put((byte) type).putInt(payload.remaining()).put(payload);
        var checksum = new CRC32C();
        checksum.update(frame.duplicate().flip());
        frame.putInt((int) checksum.getValue());
        return frame.array();
    }

    /**
     * Reads one frame from position to limit. Success advances position by exactly its size.
     * Incomplete input and format errors leave position, limit, mark and contents unchanged.
     * Empty means "more bytes required", never "this tail may be discarded".
     */
    public Optional<WalRecord> decode(ByteBuffer source) throws WalFormatException {
        Objects.requireNonNull(source, "source");
        var cursor = source.duplicate().order(ByteOrder.BIG_ENDIAN);
        if (cursor.remaining() < HEADER_BYTES) {
            return Optional.empty();
        }
        if (cursor.getInt() != MAGIC) {
            throw new WalFormatException(BAD_MAGIC, "Expected QWAL magic");
        }
        int version = Short.toUnsignedInt(cursor.getShort());
        if (version != VERSION) {
            throw new WalFormatException(UNSUPPORTED_VERSION, "Unsupported WAL version: " + version);
        }
        int type = Byte.toUnsignedInt(cursor.get());
        if (type < SET_HARD_STATE || type > SET_COMMIT_INDEX) {
            throw new WalFormatException(UNKNOWN_RECORD_TYPE, "Unknown WAL record type: " + type);
        }
        int payloadLength = cursor.getInt();
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
            throw new WalFormatException(INVALID_LENGTH, "Invalid WAL payload length: " + payloadLength);
        }
        if (cursor.remaining() < payloadLength + CHECKSUM_BYTES) {
            return Optional.empty();
        }

        // The bounds above guarantee this sum fits within the buffer's limit and cannot overflow.
        int payloadEnd = cursor.position() + payloadLength;
        var checksumBytes = source.duplicate().limit(payloadEnd);
        var checksum = new CRC32C();
        checksum.update(checksumBytes);
        long storedChecksum = Integer.toUnsignedLong(cursor.getInt(payloadEnd));
        if (checksum.getValue() != storedChecksum) {
            throw new WalFormatException(CHECKSUM_MISMATCH, "WAL CRC32C mismatch");
        }

        cursor.limit(payloadEnd);
        ByteBuffer payload = cursor.slice().order(ByteOrder.BIG_ENDIAN);
        WalRecord record = decodePayload(type, payload);
        if (payload.hasRemaining()) {
            throw new WalFormatException(INVALID_PAYLOAD, "Unexpected trailing payload bytes");
        }
        source.position(payloadEnd + CHECKSUM_BYTES);
        return Optional.of(record);
    }

    private static ByteBuffer encodePayload(WalRecord record) {
        return switch (record) {
            case WalRecord.SetHardState state -> {
                byte[] vote = state.votedFor().isPresent() ? encodeVote(state.votedFor().get()) : null;
                var payload = allocatePayload(12 + (vote == null ? 0 : vote.length))
                        .putLong(state.currentTerm()).putInt(vote == null ? -1 : vote.length);
                if (vote != null) {
                    payload.put(vote);
                }
                yield payload;
            }
            case WalRecord.AppendEntry entry -> {
                byte[] command = entry.command();
                byte entryType = switch (entry.type()) {
                    case NO_OP -> 0;
                    case COMMAND -> 1;
                };
                yield allocatePayload(21 + command.length).putLong(entry.index()).putLong(entry.term())
                        .put(entryType).putInt(command.length).put(command);
            }
            case WalRecord.TruncateSuffix truncate -> allocatePayload(8).putLong(truncate.fromIndexInclusive());
            case WalRecord.SetCommitIndex commit -> allocatePayload(8).putLong(commit.commitIndex());
        };
    }

    private static ByteBuffer allocatePayload(int size) {
        if (size < 0 || size > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("WAL payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
    }

    private static byte[] encodeVote(NodeId node) {
        // UTF-8 requires at least as many bytes as valid UTF-16 code units.
        if (node.value().length() > MAX_PAYLOAD_BYTES - 12) {
            throw new IllegalArgumentException("Node id exceeds the WAL payload limit");
        }
        try {
            var encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(node.value()));
            if (encoded.remaining() > MAX_PAYLOAD_BYTES - 12) {
                throw new IllegalArgumentException("UTF-8 node id exceeds the WAL payload limit");
            }
            byte[] result = new byte[encoded.remaining()];
            encoded.get(result);
            return result;
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Node id must contain valid Unicode", failure);
        }
    }

    private static WalRecord decodePayload(int type, ByteBuffer payload) throws WalFormatException {
        try {
            return switch (type) {
                case SET_HARD_STATE -> {
                    long term = payload.getLong();
                    int length = payload.getInt();
                    Optional<NodeId> vote = Optional.empty();
                    if (length != -1) {
                        byte[] bytes = readBytes(payload, length);
                        String node = StandardCharsets.UTF_8.newDecoder()
                                .onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT)
                                .decode(ByteBuffer.wrap(bytes)).toString();
                        vote = Optional.of(new NodeId(node));
                    }
                    yield new WalRecord.SetHardState(term, vote);
                }
                case APPEND_ENTRY -> {
                    long index = payload.getLong();
                    long term = payload.getLong();
                    int entryType = Byte.toUnsignedInt(payload.get());
                    WalRecord.EntryType kind = switch (entryType) {
                        case 0 -> WalRecord.EntryType.NO_OP;
                        case 1 -> WalRecord.EntryType.COMMAND;
                        default -> throw new WalFormatException(INVALID_PAYLOAD, "Unknown entry type: " + entryType);
                    };
                    byte[] command = readBytes(payload, payload.getInt());
                    yield new WalRecord.AppendEntry(index, term, kind, command);
                }
                case TRUNCATE_SUFFIX -> new WalRecord.TruncateSuffix(payload.getLong());
                case SET_COMMIT_INDEX -> new WalRecord.SetCommitIndex(payload.getLong());
                default -> throw new WalFormatException(UNKNOWN_RECORD_TYPE, "Unknown WAL record type: " + type);
            };
        } catch (BufferUnderflowException | IllegalArgumentException | CharacterCodingException failure) {
            throw new WalFormatException(INVALID_PAYLOAD, "Invalid fields in WAL record type " + type, failure);
        }
    }

    private static byte[] readBytes(ByteBuffer payload, int length) throws WalFormatException {
        if (length < 0 || length > payload.remaining()) {
            throw new WalFormatException(INVALID_PAYLOAD, "Byte field length exceeds its payload: " + length);
        }
        byte[] result = new byte[length];
        payload.get(result);
        return result;
    }
}
