package io.github.yergaliuly.quorumlab.core;

import java.util.List;
import java.util.Objects;

/** Fixed-membership Raft messages. Transport authenticates the sender outside the core. */
public sealed interface RaftRpc {
    int MAX_ENTRIES=64;
    int MAX_ENTRY_PAYLOAD_BYTES=1_048_576;
    long term();

    /** Empty entries still require the prev index/term check. */
    record AppendEntries(long term, OperationId requestId, long prevLogIndex, long prevLogTerm,
                         List<LogEntry> entries, long leaderCommit) implements RaftRpc {
        public AppendEntries {
            Objects.requireNonNull(requestId,"requestId"); entries=List.copyOf(entries);
            if(term<=0 || prevLogIndex<0 || prevLogTerm<0 || prevLogTerm>term || leaderCommit<0
                    || ((prevLogIndex==0)!=(prevLogTerm==0)) || entries.size()>MAX_ENTRIES) {
                throw new IllegalArgumentException("Invalid AppendEntries header");
            }
            long index=prevLogIndex, previousTerm=prevLogTerm, bytes=0;
            for(LogEntry entry:entries) {
                index=Math.incrementExact(index); bytes+=entry.payloadBytes();
                if(entry.index()!=index || entry.term()<previousTerm || entry.term()>term || bytes>MAX_ENTRY_PAYLOAD_BYTES) {
                    throw new IllegalArgumentException("Invalid AppendEntries batch");
                }
                previousTerm=entry.term();
            }
        }
        public long lastIndex() { return prevLogIndex+entries.size(); }
    }
    record AppendResponse(long term, OperationId requestId, boolean success, long matchIndex) implements RaftRpc {
        public AppendResponse {
            Objects.requireNonNull(requestId,"requestId");
            if(term<0 || (success && term==0) || matchIndex<0 || (!success && matchIndex!=0)) {
                throw new IllegalArgumentException("Invalid AppendResponse");
            }
        }
    }

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
