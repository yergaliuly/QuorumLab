package io.github.yergaliuly.quorumlab.core;

public record LogPosition(long index, long term) {
    public LogPosition {
        if (index < 0 || term < 0 || (index == 0) != (term == 0)) {
            throw new IllegalArgumentException("Log index/term must be both zero or both positive");
        }
    }
    public static LogPosition empty() { return new LogPosition(0, 0); }
}
