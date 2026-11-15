package io.github.yergaliuly.quorumlab.storage.wal;

import java.io.IOException;

/** Startup must fail closed; callers must not replace this store with an empty one. */
public final class WalRecoveryException extends IOException {
    public WalRecoveryException(String message) { super(message); }
    public WalRecoveryException(String message, Throwable cause) { super(message, cause); }
}
