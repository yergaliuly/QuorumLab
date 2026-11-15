package io.github.yergaliuly.quorumlab.storage.wal;

/** A malformed or unsupported complete header/frame. It never authorizes skipping bytes. */
public final class WalFormatException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        BAD_MAGIC,
        UNSUPPORTED_VERSION,
        UNKNOWN_RECORD_TYPE,
        INVALID_LENGTH,
        CHECKSUM_MISMATCH,
        INVALID_PAYLOAD
    }

    private final Reason reason;

    public WalFormatException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public WalFormatException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
