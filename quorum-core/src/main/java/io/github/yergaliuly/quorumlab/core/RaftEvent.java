package io.github.yergaliuly.quorumlab.core;

import java.util.Objects;

/** Immutable input data. Delivery is serialized by the driver of one node. */
public sealed interface RaftEvent {
    record TimerFired(OperationId id, TimerKind kind) implements RaftEvent {
        public TimerFired {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
        }
    }

    record RpcReceived(NodeId from, RaftRpc rpc) implements RaftEvent {
        public RpcReceived {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(rpc, "rpc");
        }
    }

    /** Success means durable persistence, not merely submission to an executor. */
    record PersistCompleted(OperationId id) implements RaftEvent {
        public PersistCompleted {
            Objects.requireNonNull(id, "id");
        }
    }

    /** Failure leaves the durable outcome unknown; it must not release a dependent reply. */
    record PersistFailed(OperationId id, String reason) implements RaftEvent {
        public PersistFailed {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(reason, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("Persistence failure must have a reason");
            }
        }
    }
}
