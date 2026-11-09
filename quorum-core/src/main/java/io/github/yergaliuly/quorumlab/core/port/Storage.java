package io.github.yergaliuly.quorumlab.core.port;

import io.github.yergaliuly.quorumlab.core.Effect;

/**
 * Driver-facing port; the core never invokes it directly.
 * Returning from persist is not an acknowledgement of durability. The adapter must
 * enqueue PersistCompleted/PersistFailed with the same id into the owning node's
 * event loop, never re-enter step from a callback. Initially supports metadata only.
 */
@FunctionalInterface
public interface Storage {
    void persist(Effect.PersistHardState request);
}
