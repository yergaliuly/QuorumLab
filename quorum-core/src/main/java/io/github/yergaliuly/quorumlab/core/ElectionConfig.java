package io.github.yergaliuly.quorumlab.core;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;

/** Fixed membership; timeouts are in nanoseconds, with an exclusive upper election bound. */
public record ElectionConfig(NodeId self, List<NodeId> voters, long incarnation,
                             long minElectionNanos, long maxElectionNanos, long heartbeatNanos) {
    public ElectionConfig {
        Objects.requireNonNull(self, "self");
        voters = List.copyOf(voters);
        if (voters.size() != 3 || new HashSet<>(voters).size() != 3 || !voters.contains(self)) {
            throw new IllegalArgumentException("Exactly three distinct voters including self are required");
        }
        if (incarnation <= 0 || heartbeatNanos <= 0 || minElectionNanos <= heartbeatNanos
                || maxElectionNanos <= minElectionNanos) {
            throw new IllegalArgumentException("Require incarnation > 0 and 0 < heartbeat < election min < max");
        }
    }
}
