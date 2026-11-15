package io.github.yergaliuly.quorumlab.core;

import java.util.List;

/**
 * Stateful, deterministic protocol boundary; RaftNode implements elections and log replication.
 * One driver calls step serially. Implementations return an immutable, non-null list.
 * A returned list is not a persistence barrier: dependent SendRpc effects must be
 * emitted by a later step after matching PersistCompleted and rechecking protocol state.
 */
@FunctionalInterface
public interface RaftCore {
    List<Effect> step(RaftEvent event);
}
