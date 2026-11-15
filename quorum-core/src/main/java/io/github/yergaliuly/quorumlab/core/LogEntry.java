package io.github.yergaliuly.quorumlab.core;

import java.util.Arrays;
import java.util.Objects;

/** Opaque deterministic command; NO_OP advances the log without changing application data. */
public record LogEntry(long index, long term, Type type, byte[] command) {
    public static final int MAX_COMMAND_BYTES = 1_048_576 - 21;
    public enum Type { NO_OP, COMMAND }
    public LogEntry {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(command, "command");
        if (index <= 0 || term <= 0 || command.length > MAX_COMMAND_BYTES
                || (type == Type.NO_OP && command.length != 0)) throw new IllegalArgumentException("Invalid log entry");
        command = command.clone();
    }
    @Override public byte[] command() { return command.clone(); }
    public int payloadBytes() { return 21 + command.length; }
    @Override public boolean equals(Object other) {
        return other instanceof LogEntry e && index == e.index && term == e.term && type == e.type && Arrays.equals(command,e.command);
    }
    @Override public int hashCode() { return 31 * Objects.hash(index,term,type) + Arrays.hashCode(command); }
    @Override public String toString() { return "LogEntry[index="+index+", term="+term+", type="+type+", bytes="+command.length+"]"; }
}
