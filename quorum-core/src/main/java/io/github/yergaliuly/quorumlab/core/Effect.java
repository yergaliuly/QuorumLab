package io.github.yergaliuly.quorumlab.core;

import java.util.Objects;

/** Descriptions of work for adapters, never I/O executed by the core itself. */
public sealed interface Effect {
    record PersistHardState(OperationId id, HardState state) implements Effect {
        public PersistHardState {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(state, "state");
        }
    }

    record SendRpc(NodeId to, RaftRpc rpc) implements Effect {
        public SendRpc {
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(rpc, "rpc");
        }
    }

    /** Delay is relative to handling this effect; id identifies this particular timer arm. */
    record ScheduleTimer(OperationId id, TimerKind kind, long delayNanos) implements Effect {
        public ScheduleTimer {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            if (delayNanos < 0) {
                throw new IllegalArgumentException("Timer delay must be nonnegative");
            }
        }
    }
}
