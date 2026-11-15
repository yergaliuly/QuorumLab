package io.github.yergaliuly.quorumlab.storage.wal;

import static org.junit.jupiter.api.Assertions.*;

import io.github.yergaliuly.quorumlab.core.NodeId;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WalRecordTest {
    @Test
    void commandIsImmutableAndComparesByContent() {
        byte[] original = {0, (byte) 255, 127};
        var entry = new WalRecord.AppendEntry(3, 2, WalRecord.EntryType.COMMAND, original);
        var equal = new WalRecord.AppendEntry(3, 2, WalRecord.EntryType.COMMAND, original.clone());
        original[0] = 9;
        entry.command()[1] = 0;
        assertArrayEquals(new byte[] {0, (byte) 255, 127}, entry.command());
        assertEquals(equal, entry);
        assertEquals(equal.hashCode(), entry.hashCode());
        assertNotEquals(new WalRecord.AppendEntry(3, 2, WalRecord.EntryType.COMMAND, new byte[] {1}), entry);
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void rejectsImpossibleValues(Executable construction) {
        assertThrows(IllegalArgumentException.class, construction);
    }

    static Stream<Executable> invalidValues() {
        return Stream.of(
                () -> new WalRecord.SetHardState(-1, Optional.empty()),
                () -> new WalRecord.SetHardState(0, Optional.of(new NodeId("n1"))),
                () -> new WalRecord.AppendEntry(0, 1, WalRecord.EntryType.COMMAND, new byte[0]),
                () -> new WalRecord.AppendEntry(1, 0, WalRecord.EntryType.COMMAND, new byte[0]),
                () -> new WalRecord.AppendEntry(1, 1, WalRecord.EntryType.NO_OP, new byte[] {1}),
                () -> new WalRecord.AppendEntry(1, 1, WalRecord.EntryType.COMMAND,
                        new byte[WalCodec.MAX_COMMAND_BYTES + 1]),
                () -> new WalRecord.TruncateSuffix(0),
                () -> new WalRecord.SetCommitIndex(-1));
    }

    @ParameterizedTest
    @MethodSource("nullValues")
    void rejectsMissingValues(Executable construction) {
        assertThrows(NullPointerException.class, construction);
    }

    static Stream<Executable> nullValues() {
        return Stream.of(
                () -> new WalRecord.SetHardState(1, null),
                () -> new WalRecord.AppendEntry(1, 1, null, new byte[0]),
                () -> new WalRecord.AppendEntry(1, 1, WalRecord.EntryType.COMMAND, null));
    }
}
