package io.github.yergaliuly.quorumlab.core;

import java.util.Objects;
import java.util.List;
import java.util.Optional;

/** Descriptions of work for adapters, never I/O executed by the core itself. */
public sealed interface Effect {
    record PersistBatch(OperationId id, HardState state, long truncateFromIndex, List<LogEntry> entries) implements Effect {
        public PersistBatch(OperationId id, HardState state) { this(id,state,0,List.of()); }
        public PersistBatch {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(state, "state");
            entries=List.copyOf(entries);
            if(truncateFromIndex<0 || (truncateFromIndex>0 && !entries.isEmpty() && entries.getFirst().index()!=truncateFromIndex)) {
                throw new IllegalArgumentException("Invalid truncation");
            }
            validateEntries(entries);
        }
    }

    record ApplyCommittedEntries(OperationId id, List<LogEntry> entries) implements Effect {
        public ApplyCommittedEntries {
            Objects.requireNonNull(id,"id"); entries=List.copyOf(entries);
            if(entries.isEmpty() || entries.size()>RaftRpc.MAX_ENTRIES) throw new IllegalArgumentException("Invalid apply batch size");
            validateEntries(entries);
        }
    }

    /** Admission only, NOT persistence, commitment or client success. */
    record ProposalQueued(long index, long term) implements Effect {
        public ProposalQueued { if(index<=0 || term<=0) throw new IllegalArgumentException("Invalid proposal position"); }
    }
    record ProposalRejected(Reason reason, Optional<NodeId> leaderHint) implements Effect {
        public enum Reason { NOT_LEADER, BUSY, FAILED }
        public ProposalRejected { Objects.requireNonNull(reason,"reason"); Objects.requireNonNull(leaderHint,"leaderHint"); }
    }

    private static void validateEntries(List<LogEntry> entries) {
        for(int i=1;i<entries.size();i++) {
            if(entries.get(i).index()!=Math.incrementExact(entries.get(i-1).index()) || entries.get(i).term()<entries.get(i-1).term()) {
                throw new IllegalArgumentException("Entries must be contiguous with nondecreasing terms");
            }
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
