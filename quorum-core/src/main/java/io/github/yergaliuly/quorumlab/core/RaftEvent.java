package io.github.yergaliuly.quorumlab.core;

import java.util.Objects;

/** Immutable input data. Delivery is serialized by the driver of one node. */
public sealed interface RaftEvent {
    /** Internal admission input; M4 will define client identity/deduplication and completion. */
    record Propose(byte[] command) implements RaftEvent {
        public Propose {
            Objects.requireNonNull(command,"command");
            if(command.length>LogEntry.MAX_COMMAND_BYTES) throw new IllegalArgumentException("Command too large");
            command=command.clone();
        }
        @Override public byte[] command() { return command.clone(); }
        @Override public String toString() { return "Propose[bytes="+command.length+"]"; }
    }
    record ApplyCompleted(OperationId id) implements RaftEvent {
        public ApplyCompleted { Objects.requireNonNull(id,"id"); }
    }
    record ApplyFailed(OperationId id, String reason) implements RaftEvent {
        public ApplyFailed {
            Objects.requireNonNull(id,"id"); Objects.requireNonNull(reason,"reason");
            if(reason.isBlank()) throw new IllegalArgumentException("Apply failure must have a reason");
        }
    }
    /** Driver starts a recovered node once, after its identity and storage are ready. */
    record Start() implements RaftEvent { }

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
