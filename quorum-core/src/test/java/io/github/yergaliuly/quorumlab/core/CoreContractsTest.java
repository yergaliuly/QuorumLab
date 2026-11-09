package io.github.yergaliuly.quorumlab.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CoreContractsTest {
    private static final NodeId A = new NodeId("a");
    private static final OperationId ID = new OperationId(1, 1);

    @Test
    void initialMetadataHasNoTermVoteOrCommittedEntry() {
        assertEquals(new HardState(0, Optional.empty(), 0), HardState.initial());
    }

    @Test
    void committedIndexIsNotBoundedByTermNumber() {
        assertEquals(100, new HardState(2, Optional.of(A), 100).commitIndex());
    }

    @Test
    void recordsCarryValueIdentityAndRestartSeparatesCompletions() {
        var state = new HardState(3, Optional.of(A), 7);
        assertEquals(state, new HardState(3, Optional.of(new NodeId("a")), 7));
        assertEquals(new Effect.PersistHardState(ID, state),
                new Effect.PersistHardState(new OperationId(1, 1), state));
        assertNotEquals(new RaftEvent.PersistCompleted(ID),
                new RaftEvent.PersistCompleted(new OperationId(2, 1)));
    }

    @Test
    void validEventsAndEffectsRetainTheirData() {
        var rpc = new RaftRpc.RequestVote(3, 7, 2);
        assertEquals(rpc, new RaftEvent.RpcReceived(A, rpc).rpc());
        assertEquals(A, new Effect.SendRpc(A, rpc).to());
        assertEquals("disk failure", new RaftEvent.PersistFailed(ID, "disk failure").reason());
        assertEquals(ID, new RaftEvent.TimerFired(ID, TimerKind.ELECTION).id());
        assertEquals(0, new Effect.ScheduleTimer(ID, TimerKind.HEARTBEAT, 0).delayNanos());
        assertDoesNotThrow(() -> new RaftRpc.RequestVote(1, 0, 0));
        assertDoesNotThrow(() -> new RaftRpc.VoteResponse(0, false));
        assertDoesNotThrow(() -> new RaftRpc.VoteResponse(3, true));
    }

    @ParameterizedTest
    @MethodSource("invalidValues")
    void rejectsInvalidValues(Executable construction) {
        assertThrows(IllegalArgumentException.class, construction);
    }

    static Stream<Executable> invalidValues() {
        return Stream.of(
                () -> new NodeId(" "),
                () -> new OperationId(0, 1),
                () -> new OperationId(1, 0),
                () -> new HardState(-1, Optional.empty(), 0),
                () -> new HardState(1, Optional.empty(), -1),
                () -> new HardState(0, Optional.of(A), 0),
                () -> new HardState(0, Optional.empty(), 1),
                () -> new RaftRpc.RequestVote(0, 0, 0),
                () -> new RaftRpc.RequestVote(1, -1, 0),
                () -> new RaftRpc.RequestVote(1, 1, -1),
                () -> new RaftRpc.RequestVote(1, 0, 1),
                () -> new RaftRpc.RequestVote(1, 1, 0),
                () -> new RaftRpc.RequestVote(1, 1, 2),
                () -> new RaftRpc.VoteResponse(-1, false),
                () -> new RaftRpc.VoteResponse(0, true),
                () -> new RaftEvent.PersistFailed(ID, " "),
                () -> new Effect.ScheduleTimer(ID, TimerKind.ELECTION, -1));
    }

    @ParameterizedTest
    @MethodSource("missingValues")
    void rejectsMissingValues(Executable construction) {
        assertThrows(NullPointerException.class, construction);
    }

    static Stream<Executable> missingValues() {
        return Stream.of(
                () -> new NodeId(null),
                () -> new HardState(1, null, 0),
                () -> new RaftEvent.TimerFired(null, TimerKind.ELECTION),
                () -> new RaftEvent.TimerFired(ID, null),
                () -> new RaftEvent.RpcReceived(null, new RaftRpc.RequestVote(1, 0, 0)),
                () -> new RaftEvent.RpcReceived(A, null),
                () -> new RaftEvent.PersistCompleted(null),
                () -> new RaftEvent.PersistFailed(null, "failure"),
                () -> new RaftEvent.PersistFailed(ID, null),
                () -> new Effect.PersistHardState(null, HardState.initial()),
                () -> new Effect.PersistHardState(ID, null),
                () -> new Effect.SendRpc(null, new RaftRpc.VoteResponse(1, false)),
                () -> new Effect.SendRpc(A, null),
                () -> new Effect.ScheduleTimer(null, TimerKind.ELECTION, 1),
                () -> new Effect.ScheduleTimer(ID, null, 1));
    }
}
