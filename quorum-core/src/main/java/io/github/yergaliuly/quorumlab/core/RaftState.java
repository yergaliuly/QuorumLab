package io.github.yergaliuly.quorumlab.core;

import java.util.List;
import java.util.Objects;

/** Full recovered prefix; snapshots/log compaction are outside M3. */
public record RaftState(HardState hardState, List<LogEntry> log) {
    public RaftState {
        Objects.requireNonNull(hardState,"hardState"); log=List.copyOf(log);
        long index=0, term=0;
        for (LogEntry entry:log) {
            if (entry.index()!=++index || entry.term()<term || entry.term()>hardState.currentTerm()) {
                throw new IllegalArgumentException("Invalid recovered log");
            }
            term=entry.term();
        }
        if(hardState.commitIndex()>log.size()) throw new IllegalArgumentException("Commit exceeds log");
    }
    public static RaftState initial() { return new RaftState(HardState.initial(),List.of()); }
    public LogPosition lastLogPosition() { return log.isEmpty()? LogPosition.empty():new LogPosition(log.getLast().index(),log.getLast().term()); }
}
