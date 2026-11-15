package io.github.yergaliuly.quorumlab.core;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;

/** Fixed voters and exclusive upper election bound; append batching is bounded independently of timing. */
public record RaftConfig(NodeId self, List<NodeId> voters, long incarnation,
                         long minElectionNanos, long maxElectionNanos, long heartbeatNanos, int maxAppendEntries) {
    public RaftConfig(NodeId self,List<NodeId> voters,long incarnation,long min,long max,long heartbeat) {
        this(self,voters,incarnation,min,max,heartbeat,RaftRpc.MAX_ENTRIES);
    }
    public RaftConfig {
        Objects.requireNonNull(self,"self"); voters=List.copyOf(voters);
        if(voters.size()!=3 || new HashSet<>(voters).size()!=3 || !voters.contains(self)) {
            throw new IllegalArgumentException("Exactly three distinct voters including self are required");
        }
        if(incarnation<=0 || heartbeatNanos<=0 || minElectionNanos<=heartbeatNanos || maxElectionNanos<=minElectionNanos
                || maxAppendEntries<1 || maxAppendEntries>RaftRpc.MAX_ENTRIES) throw new IllegalArgumentException("Invalid Raft configuration");
    }
}
