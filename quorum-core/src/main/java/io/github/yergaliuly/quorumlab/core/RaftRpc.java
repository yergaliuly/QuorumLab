package io.github.yergaliuly.quorumlab.core;

/** Initial election messages; replication and snapshots will extend this protocol later. */
public sealed interface RaftRpc {
    /** The candidate is the sender in RpcReceived; index/term 0 denote an empty log. */
    record RequestVote(long term, long lastLogIndex, long lastLogTerm) implements RaftRpc {
        public RequestVote {
            if (term <= 0 || lastLogIndex < 0 || lastLogTerm < 0 || lastLogTerm > term) {
                throw new IllegalArgumentException("Invalid candidate term or log position");
            }
            if ((lastLogIndex == 0) != (lastLogTerm == 0)) {
                throw new IllegalArgumentException("Empty log must have both index and term zero");
            }
        }
    }

    record VoteResponse(long term, boolean granted) implements RaftRpc {
        public VoteResponse {
            if (term < 0 || (term == 0 && granted)) {
                throw new IllegalArgumentException("A granted vote requires a positive term");
            }
        }
    }
}
