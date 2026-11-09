package io.github.yergaliuly.quorumlab.core;

import java.util.Optional;
import java.util.Objects;

/** Durable metadata. Referenced committed log entries must already be durable. */
public record HardState(long currentTerm, Optional<NodeId> votedFor, long commitIndex) {
    public HardState {
        Objects.requireNonNull(votedFor, "votedFor");
        if (currentTerm < 0 || commitIndex < 0) {
            throw new IllegalArgumentException("Term and commit index must be nonnegative");
        }
        if (currentTerm == 0 && (votedFor.isPresent() || commitIndex != 0)) {
            throw new IllegalArgumentException("Initial term cannot have a vote or committed entries");
        }
    }

    public static HardState initial() {
        return new HardState(0, Optional.empty(), 0);
    }
}
