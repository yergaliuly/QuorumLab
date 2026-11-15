package io.github.yergaliuly.quorumlab.storage.wal;

import static org.junit.jupiter.api.Assertions.*;

import io.github.yergaliuly.quorumlab.core.HardState;
import io.github.yergaliuly.quorumlab.core.NodeId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class WalStateTest {
    static final NodeId A = new NodeId("a");
    static WalRecord.SetHardState term(long term) { return new WalRecord.SetHardState(term, Optional.empty()); }
    static WalRecord.AppendEntry entry(long index, long term) {
        return new WalRecord.AppendEntry(index, term, WalRecord.EntryType.COMMAND, new byte[]{(byte) index});
    }
    static WalState base() {
        return WalState.initial().apply(List.of(new WalRecord.SetHardState(2, Optional.of(A)),
                entry(1, 1), entry(2, 2), new WalRecord.SetCommitIndex(1)));
    }

    @Test void buildsImmutableStateWithoutChangingPreviousSnapshot() {
        WalState before = base();
        WalState after = before.apply(List.of(new WalRecord.TruncateSuffix(2), term(3),
                entry(2, 3), new WalRecord.SetCommitIndex(2)));
        assertEquals(new HardState(2, Optional.of(A), 1), before.hardState());
        assertEquals(List.of(entry(1, 1), entry(2, 2)), before.entries());
        assertEquals(new HardState(3, Optional.empty(), 2), after.hardState());
        assertEquals(List.of(entry(1, 1), entry(2, 3)), after.entries());
        assertThrows(UnsupportedOperationException.class, () -> after.entries().clear());
    }

    static Stream<WalRecord> invalidTransitions() {
        return Stream.of(term(1), term(2), new WalRecord.SetHardState(2, Optional.of(new NodeId("b"))),
                entry(2, 2), entry(4, 2), entry(3, 1), entry(3, 3),
                new WalRecord.SetCommitIndex(0), new WalRecord.SetCommitIndex(3),
                new WalRecord.TruncateSuffix(1), new WalRecord.TruncateSuffix(4));
    }
    @ParameterizedTest @MethodSource("invalidTransitions")
    void rejectsInvalidHistory(WalRecord invalid) {
        WalState before = base();
        assertThrows(IllegalArgumentException.class, () -> before.apply(List.of(invalid)));
        assertEquals(2, before.entries().size());
    }

    @Test void validatesEveryRecordBeforePublishingBatch() {
        WalState before = base();
        assertThrows(IllegalArgumentException.class, () -> before.apply(List.of(term(3), entry(4, 3))));
        assertEquals(2, before.hardState().currentTerm());
    }

    @Test void allowsIdempotentMetadataAndTruncateAtEnd() {
        WalState before = base();
        WalState after = before.apply(List.of(new WalRecord.SetHardState(2, Optional.of(A)),
                new WalRecord.TruncateSuffix(3), new WalRecord.SetCommitIndex(1)));
        assertEquals(before.hardState(), after.hardState());
        assertEquals(before.entries(), after.entries());
    }

    @Test void canGrantPreviouslyEmptyVoteInSameTerm() {
        WalState state = WalState.initial().apply(List.of(term(1), new WalRecord.SetHardState(1, Optional.of(A))));
        assertEquals(Optional.of(A), state.hardState().votedFor());
    }
}
