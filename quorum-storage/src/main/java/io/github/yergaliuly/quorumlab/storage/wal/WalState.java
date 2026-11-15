package io.github.yergaliuly.quorumlab.storage.wal;

import io.github.yergaliuly.quorumlab.core.HardState;
import io.github.yergaliuly.quorumlab.core.NodeId;
import io.github.yergaliuly.quorumlab.core.LogEntry;
import io.github.yergaliuly.quorumlab.core.RaftState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable recovered log and metadata. Uncommitted entries remain in the log, never applied here. */
public final class WalState {
    private final HardState hardState;
    private final List<WalRecord.AppendEntry> entries;

    private WalState(HardState hardState, List<WalRecord.AppendEntry> entries) {
        this.hardState = hardState;
        this.entries = List.copyOf(entries);
    }

    static WalState initial() { return new WalState(HardState.initial(), List.of()); }
    public HardState hardState() { return hardState; }
    public List<WalRecord.AppendEntry> entries() { return entries; }
    public RaftState toRaftState() {
        return new RaftState(hardState,entries.stream().map(entry->new LogEntry(entry.index(),entry.term(),
                LogEntry.Type.valueOf(entry.type().name()),entry.command())).toList());
    }

    WalState apply(List<WalRecord> records) {
        var builder = new Builder(this);
        records.forEach(builder::apply);
        return builder.snapshot();
    }

    // One mutable builder for streaming replay; one copy per batch, not one per record.
    static final class Builder {
        private long term;
        private Optional<NodeId> vote;
        private long commit;
        private final ArrayList<WalRecord.AppendEntry> entries;

        Builder(WalState state) {
            term = state.hardState.currentTerm();
            vote = state.hardState.votedFor();
            commit = state.hardState.commitIndex();
            entries = new ArrayList<>(state.entries);
        }

        void apply(WalRecord record) {
            Objects.requireNonNull(record, "record");
            switch (record) {
                case WalRecord.SetHardState next -> {
                    require(next.currentTerm() >= term, "currentTerm cannot decrease");
                    require(next.currentTerm() != term || vote.isEmpty() || vote.equals(next.votedFor()),
                            "Cannot revoke or change a vote in the same term");
                    term = next.currentTerm();
                    vote = next.votedFor();
                }
                case WalRecord.AppendEntry next -> {
                    require(next.index() == entries.size() + 1L, "Append index must follow the log end");
                    require(next.term() <= term, "Entry term exceeds currentTerm");
                    require(entries.isEmpty() || next.term() >= entries.getLast().term(), "Log terms cannot decrease");
                    entries.add(next);
                }
                case WalRecord.TruncateSuffix next -> {
                    long from = next.fromIndexInclusive();
                    require(from > commit, "Cannot truncate the committed prefix");
                    require(from <= entries.size() + 1L, "Truncate index exceeds log end + 1");
                    entries.subList(Math.toIntExact(from - 1), entries.size()).clear();
                }
                case WalRecord.SetCommitIndex next -> {
                    require(next.commitIndex() >= commit, "commitIndex cannot decrease");
                    require(next.commitIndex() <= entries.size(), "Commit refers to a missing log entry");
                    commit = next.commitIndex();
                }
            }
        }

        WalState snapshot() { return new WalState(new HardState(term, vote, commit), entries); }
        private static void require(boolean condition, String message) {
            if (!condition) throw new IllegalArgumentException(message);
        }
    }
}
