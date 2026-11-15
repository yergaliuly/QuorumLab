package io.github.yergaliuly.quorumlab.storage.wal;

import io.github.yergaliuly.quorumlab.core.NodeId;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Operations stored in the append-only WAL; stateful replay checks belong to Storage. */
public sealed interface WalRecord {
    record SetHardState(long currentTerm, Optional<NodeId> votedFor) implements WalRecord {
        public SetHardState {
            Objects.requireNonNull(votedFor, "votedFor");
            if (currentTerm < 0 || (currentTerm == 0 && votedFor.isPresent())) {
                throw new IllegalArgumentException("Term must be nonnegative; term zero cannot have a vote");
            }
        }
    }

    enum EntryType {
        NO_OP,
        COMMAND
    }

    record AppendEntry(long index, long term, EntryType type, byte[] command) implements WalRecord {
        public AppendEntry {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(command, "command");
            if (index <= 0 || term <= 0) {
                throw new IllegalArgumentException("Log index and term must be positive");
            }
            if (command.length > WalCodec.MAX_COMMAND_BYTES) {
                throw new IllegalArgumentException("Command exceeds the WAL payload limit");
            }
            if (type == EntryType.NO_OP && command.length != 0) {
                throw new IllegalArgumentException("NO_OP must have an empty command");
            }
            command = command.clone();
        }

        @Override
        public byte[] command() {
            return command.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof AppendEntry that && index == that.index && term == that.term
                    && type == that.type && Arrays.equals(command, that.command);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(index, term, type) + Arrays.hashCode(command);
        }

        @Override
        public String toString() {
            return "AppendEntry[index=" + index + ", term=" + term + ", type=" + type
                    + ", commandBytes=" + command.length + "]";
        }
    }

    record TruncateSuffix(long fromIndexInclusive) implements WalRecord {
        public TruncateSuffix {
            if (fromIndexInclusive <= 0) {
                throw new IllegalArgumentException("Truncate index must be positive");
            }
        }
    }

    record SetCommitIndex(long commitIndex) implements WalRecord {
        public SetCommitIndex {
            if (commitIndex < 0) {
                throw new IllegalArgumentException("Commit index must be nonnegative");
            }
        }
    }
}
