package io.github.yergaliuly.quorumlab.core.port;

import io.github.yergaliuly.quorumlab.core.Effect;

/** Driver-facing port. Submission does not imply receipt; messages may be lost or duplicated. */
@FunctionalInterface
public interface Transport {
    void send(Effect.SendRpc request);
}
