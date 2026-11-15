package io.github.yergaliuly.quorumlab.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ElectionCoreTest {
    static final NodeId A = new NodeId("a"), B = new NodeId("b"), C = new NodeId("c");
    static final List<NodeId> VOTERS = List.of(A, B, C);
    static ElectionConfig config(long incarnation) { return new ElectionConfig(A, VOTERS, incarnation, 100, 200, 20); }
    static ElectionCore fresh() { return new ElectionCore(config(1), HardState.initial(), LogPosition.empty(), (a, b) -> a); }
    static RaftEvent rpc(NodeId from, RaftRpc message) { return new RaftEvent.RpcReceived(from, message); }
    static RaftEvent fire(Effect.ScheduleTimer timer) { return new RaftEvent.TimerFired(timer.id(), timer.kind()); }
    static <T extends Effect> T one(List<Effect> effects, Class<T> type) {
        var matches = effects.stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, matches.size(), effects.toString()); return matches.getFirst();
    }
    static List<Effect.SendRpc> sends(List<Effect> effects) { return effects.stream().filter(Effect.SendRpc.class::isInstance).map(Effect.SendRpc.class::cast).toList(); }
    static void noSends(List<Effect> effects) { assertTrue(sends(effects).isEmpty(), effects.toString()); }
    static Effect.PersistHardState begin(ElectionCore core) {
        var timer = one(core.step(new RaftEvent.Start()), Effect.ScheduleTimer.class);
        return one(core.step(fire(timer)), Effect.PersistHardState.class);
    }
    static List<Effect> complete(ElectionCore core, Effect.PersistHardState persist) {
        return core.step(new RaftEvent.PersistCompleted(persist.id()));
    }

    @Test void startupIsIdempotentAndDoesNotUseInputBeforeStart() {
        var core = fresh(); assertTrue(core.step(rpc(B, new RaftRpc.RequestVote(8, 0, 0))).isEmpty());
        var first = core.step(new RaftEvent.Start());
        assertEquals(ElectionCore.Role.FOLLOWER, core.status().role());
        assertEquals(100, one(first, Effect.ScheduleTimer.class).delayNanos());
        assertThrows(UnsupportedOperationException.class, () -> first.clear());
        assertTrue(core.step(new RaftEvent.Start()).isEmpty());
        assertEquals(0, core.status().hardState().currentTerm());
    }

    @Test void selfVoteMustPersistBeforeAnyRequestOrLeadership() {
        var core = fresh(); var timer = one(core.step(new RaftEvent.Start()), Effect.ScheduleTimer.class);
        var effects = core.step(fire(timer)); var persist = one(effects, Effect.PersistHardState.class);
        assertEquals(new HardState(1, Optional.of(A), 0), persist.state()); noSends(effects);
        assertEquals(ElectionCore.Role.CANDIDATE, core.status().role());
        assertTrue(core.status().votes().isEmpty());
        noSends(core.step(rpc(B, new RaftRpc.VoteResponse(1, true))));
        assertEquals(ElectionCore.Role.CANDIDATE, core.status().role());
        var requests = sends(complete(core, persist));
        assertEquals(List.of(new Effect.SendRpc(B, new RaftRpc.RequestVote(1, 0, 0)),
                new Effect.SendRpc(C, new RaftRpc.RequestVote(1, 0, 0))), requests);
        assertEquals(Set.of(A), core.status().votes());
        assertTrue(complete(core, persist).isEmpty());
    }

    @Test void majorityElectsLeaderAndHeartbeatsRepeatOnlyOnCurrentTimer() {
        var core = fresh(); complete(core, begin(core));
        noSends(core.step(rpc(C, new RaftRpc.VoteResponse(1, false))));
        var elected = core.step(rpc(B, new RaftRpc.VoteResponse(1, true)));
        assertEquals(ElectionCore.Role.LEADER, core.status().role());
        assertEquals(Set.of(A, B), core.status().votes());
        assertEquals(2, sends(elected).size());
        assertTrue(sends(elected).stream().allMatch(effect -> effect.rpc().equals(new RaftRpc.Heartbeat(1))));
        var heartbeat = one(elected, Effect.ScheduleTimer.class);
        assertEquals(TimerKind.HEARTBEAT, heartbeat.kind());
        var tick = core.step(fire(heartbeat)); assertEquals(2, sends(tick).size());
        assertNotEquals(heartbeat.id(), one(tick, Effect.ScheduleTimer.class).id());
        assertTrue(core.step(fire(heartbeat)).isEmpty());
        assertTrue(core.step(rpc(B, new RaftRpc.VoteResponse(1, true))).isEmpty());
    }

    @Test void grantWaitsForDurabilityAndCompetingRequestCannotReplaceChosenVote() {
        var core = fresh(); core.step(new RaftEvent.Start());
        var grant = core.step(rpc(B, new RaftRpc.RequestVote(1, 0, 0)));
        var persist = one(grant, Effect.PersistHardState.class); noSends(grant);
        noSends(core.step(rpc(C, new RaftRpc.RequestVote(1, 0, 0))));
        assertEquals(Optional.of(B), core.status().hardState().votedFor());
        assertEquals(List.of(new Effect.SendRpc(B, new RaftRpc.VoteResponse(1, true))), sends(complete(core, persist)));
        assertEquals(List.of(new Effect.SendRpc(C, new RaftRpc.VoteResponse(1, false))), sends(core.step(rpc(C, new RaftRpc.RequestVote(1, 0, 0)))));
        var repeat = core.step(rpc(B, new RaftRpc.RequestVote(1, 0, 0)));
        assertEquals(List.of(new Effect.SendRpc(B, new RaftRpc.VoteResponse(1, true))), sends(repeat));
        assertTrue(repeat.stream().noneMatch(Effect.PersistHardState.class::isInstance));
    }

    static Stream<Arguments> freshness() {
        return Stream.of(Arguments.of(100L, 2L, false), Arguments.of(9L, 3L, false),
                Arguments.of(10L, 3L, true), Arguments.of(11L, 3L, true),
                Arguments.of(2L, 4L, true), Arguments.of(0L, 0L, false));
    }
    @ParameterizedTest @MethodSource("freshness")
    void logFreshnessComparesTermBeforeIndex(long index, long term, boolean granted) {
        var core = new ElectionCore(config(1), new HardState(5, Optional.empty(), 7), new LogPosition(10, 3), (a,b) -> a);
        core.step(new RaftEvent.Start());
        var effects = core.step(rpc(B, new RaftRpc.RequestVote(6, index, term)));
        noSends(effects); var persist = one(effects, Effect.PersistHardState.class);
        assertEquals(7, persist.state().commitIndex());
        assertEquals(List.of(new Effect.SendRpc(B, new RaftRpc.VoteResponse(6, granted))), sends(complete(core, persist)));
    }

    @Test void higherTermDuringPersistSerializesWritesAndCancelsOldCampaign() {
        var core = fresh(); var first = begin(core);
        var newer = core.step(rpc(B, new RaftRpc.RequestVote(2, 0, 0)));
        noSends(newer); assertTrue(newer.stream().noneMatch(Effect.PersistHardState.class::isInstance));
        assertEquals(ElectionCore.Role.FOLLOWER, core.status().role());
        assertEquals(2, core.status().hardState().currentTerm());
        var next = complete(core, first); noSends(next);
        var second = one(next, Effect.PersistHardState.class);
        assertEquals(new HardState(2, Optional.of(B), 0), second.state());
        assertEquals(1, core.status().durableState().currentTerm());
        assertTrue(complete(core, first).isEmpty());
        assertEquals(List.of(new Effect.SendRpc(B, new RaftRpc.VoteResponse(2, true))), sends(complete(core, second)));
    }

    @Test void newlyGrantedVoteDuringTermOnlyPersistNeedsAnotherPersist() {
        var core = fresh(); core.step(new RaftEvent.Start());
        var first = one(core.step(rpc(B, new RaftRpc.VoteResponse(3, false))), Effect.PersistHardState.class);
        assertEquals(Optional.empty(), first.state().votedFor());
        noSends(core.step(rpc(C, new RaftRpc.RequestVote(3, 0, 0))));
        var effects = complete(core, first); noSends(effects);
        var second = one(effects, Effect.PersistHardState.class);
        assertEquals(Optional.of(C), second.state().votedFor());
        assertEquals(List.of(new Effect.SendRpc(C, new RaftRpc.VoteResponse(3, true))), sends(complete(core, second)));
    }

    @Test void multipleHigherTermsCoalesceWithoutSendingObsoleteGrant() {
        var core = fresh(); var first = begin(core);
        core.step(rpc(B, new RaftRpc.RequestVote(2, 0, 0)));
        core.step(rpc(C, new RaftRpc.VoteResponse(4, false)));
        var next = one(complete(core, first), Effect.PersistHardState.class);
        assertEquals(new HardState(4, Optional.empty(), 0), next.state());
        noSends(complete(core, next));
    }

    @Test void sameTermHeartbeatCancelsCandidateEvenBeforeSelfVoteCompletes() {
        var core = fresh(); var first = begin(core);
        var contact = core.step(rpc(B, new RaftRpc.Heartbeat(1))); noSends(contact);
        assertEquals(ElectionCore.Role.FOLLOWER, core.status().role());
        assertEquals(Optional.of(B), core.status().leader());
        noSends(complete(core, first));
        assertEquals(Optional.of(A), core.status().hardState().votedFor());
    }

    @Test void leaderStepsDownOnHigherResponseAndOldHeartbeatTimerCannotSend() {
        var core = fresh(); complete(core, begin(core));
        var oldTimer = one(core.step(rpc(B, new RaftRpc.VoteResponse(1, true))), Effect.ScheduleTimer.class);
        var down = core.step(rpc(C, new RaftRpc.VoteResponse(5, false)));
        assertEquals(ElectionCore.Role.FOLLOWER, core.status().role()); noSends(down);
        var persist = one(down, Effect.PersistHardState.class);
        assertEquals(new HardState(5, Optional.empty(), 0), persist.state());
        assertEquals(TimerKind.ELECTION, one(down, Effect.ScheduleTimer.class).kind());
        assertTrue(core.step(fire(oldTimer)).isEmpty()); noSends(complete(core, persist));
    }

    @Test void rejectedVotesAndOldHeartbeatsDoNotPostponeElection() {
        var core = new ElectionCore(config(1), new HardState(5, Optional.empty(), 0), new LogPosition(10, 3), (a,b) -> a);
        var timer = one(core.step(new RaftEvent.Start()), Effect.ScheduleTimer.class);
        for (int i=0; i<20; i++) {
            var denied = core.step(rpc(B, new RaftRpc.RequestVote(5, 9, 3)));
            assertEquals(1, denied.size()); assertInstanceOf(Effect.SendRpc.class, denied.getFirst());
            assertTrue(core.step(rpc(C, new RaftRpc.Heartbeat(4))).isEmpty());
        }
        assertEquals(6, one(core.step(fire(timer)), Effect.PersistHardState.class).state().currentTerm());
    }

    @Test void pendingPersistenceTimeoutRearmsWithoutStartingConcurrentWrite() {
        var core = fresh(); var initial = one(core.step(new RaftEvent.Start()), Effect.ScheduleTimer.class);
        var effects = core.step(fire(initial)); var first = one(effects, Effect.PersistHardState.class);
        var timer = one(effects, Effect.ScheduleTimer.class);
        var waiting = core.step(fire(timer)); noSends(waiting);
        assertEquals(1, waiting.size()); assertInstanceOf(Effect.ScheduleTimer.class, waiting.getFirst());
        assertEquals(1, core.status().hardState().currentTerm());
        assertEquals(2, sends(complete(core, first)).size());
    }

    @Test void staleIdsWrongKindsAndUnknownMembersHaveNoEffect() {
        var core = fresh(); var timer = one(core.step(new RaftEvent.Start()), Effect.ScheduleTimer.class);
        assertTrue(core.step(new RaftEvent.TimerFired(timer.id(), TimerKind.HEARTBEAT)).isEmpty());
        assertTrue(core.step(new RaftEvent.TimerFired(new OperationId(9, 1), TimerKind.ELECTION)).isEmpty());
        assertTrue(core.step(rpc(new NodeId("outsider"), new RaftRpc.RequestVote(99, 0, 0))).isEmpty());
        assertTrue(core.step(rpc(A, new RaftRpc.Heartbeat(100))).isEmpty());
        var first = one(core.step(fire(timer)), Effect.PersistHardState.class);
        assertTrue(core.step(new RaftEvent.PersistCompleted(new OperationId(9, first.id().sequence()))).isEmpty());
        assertTrue(core.step(new RaftEvent.PersistFailed(new OperationId(9, first.id().sequence()), "old failure")).isEmpty());
        assertEquals(2, sends(complete(core, first)).size());
    }

    @Test void matchingFailureStopsNodeEvenIfItAlreadyObservedHigherTerm() {
        var core = fresh(); var first = begin(core);
        core.step(rpc(B, new RaftRpc.Heartbeat(3)));
        assertTrue(core.step(new RaftEvent.PersistFailed(first.id(), "ENOSPC")).isEmpty());
        assertEquals(ElectionCore.Role.FAILED, core.status().role());
        assertTrue(core.status().failure().orElseThrow().contains("ENOSPC"));
        assertTrue(complete(core, first).isEmpty());
        assertTrue(core.step(rpc(B, new RaftRpc.RequestVote(5, 0, 0))).isEmpty());
        assertTrue(core.step(new RaftEvent.Start()).isEmpty());
    }

    @Test void recoveredVotePreventsDoubleVoteAndOldIncarnationCannotCompleteNewPersist() {
        var core = new ElectionCore(config(2), new HardState(4, Optional.of(B), 0), LogPosition.empty(), (a,b) -> a);
        core.step(new RaftEvent.Start());
        assertEquals(List.of(new Effect.SendRpc(C, new RaftRpc.VoteResponse(4, false))), sends(core.step(rpc(C, new RaftRpc.RequestVote(4, 0, 0)))));
        var next = one(core.step(rpc(C, new RaftRpc.RequestVote(5, 0, 0))), Effect.PersistHardState.class);
        assertEquals(2, next.id().incarnation());
        assertTrue(core.step(new RaftEvent.PersistCompleted(new OperationId(1, next.id().sequence()))).isEmpty());
        assertEquals(List.of(new Effect.SendRpc(C, new RaftRpc.VoteResponse(5, true))), sends(complete(core, next)));
    }

    @Test void termExhaustionAndBrokenRandomSourceFailClosed() {
        var core = new ElectionCore(config(1), new HardState(Long.MAX_VALUE, Optional.empty(), 0), LogPosition.empty(), (a,b) -> a);
        var timer = one(core.step(new RaftEvent.Start()), Effect.ScheduleTimer.class);
        assertTrue(core.step(fire(timer)).isEmpty()); assertEquals(ElectionCore.Role.FAILED, core.status().role());
        var broken = new ElectionCore(config(1), HardState.initial(), LogPosition.empty(), (a,b) -> b);
        assertTrue(broken.step(new RaftEvent.Start()).isEmpty()); assertEquals(ElectionCore.Role.FAILED, broken.status().role());
    }

    @Test void validatesRecoveredStateAgainstMembershipAndLastLog() {
        assertThrows(IllegalArgumentException.class, () -> new ElectionCore(config(1), new HardState(1, Optional.empty(), 2), new LogPosition(1,1), (a,b)->a));
        assertThrows(IllegalArgumentException.class, () -> new ElectionCore(config(1), new HardState(1, Optional.empty(), 0), new LogPosition(1,2), (a,b)->a));
        assertThrows(IllegalArgumentException.class, () -> new ElectionCore(config(1), new HardState(1, Optional.of(new NodeId("x")), 0), LogPosition.empty(), (a,b)->a));
    }

    @ParameterizedTest @ValueSource(longs = {0, -1})
    void heartbeatRequiresPositiveTerm(long term) { assertThrows(IllegalArgumentException.class, () -> new RaftRpc.Heartbeat(term)); }

    @Test void configRequiresThreeDistinctMembersAndValidTimingAndCopiesInput() {
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(A, List.of(A,B), 1,100,200,20));
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(A, List.of(A,B,B), 1,100,200,20));
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(new NodeId("x"), VOTERS, 1,100,200,20));
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(A, VOTERS, 0,100,200,20));
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(A, VOTERS, 1,0,200,20));
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(A, VOTERS, 1,100,100,20));
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(A, VOTERS, 1,100,200,0));
        assertThrows(IllegalArgumentException.class, () -> new ElectionConfig(A, VOTERS, 1,100,200,100));
        var mutable = new ArrayList<>(VOTERS); var config = new ElectionConfig(A, mutable, 1,100,200,20); mutable.clear();
        assertEquals(VOTERS, config.voters()); assertThrows(UnsupportedOperationException.class, () -> config.voters().clear());
    }

    @Test void validatesEmptyAndNonemptyLogPositions() {
        assertEquals(new LogPosition(0,0), LogPosition.empty());
        assertThrows(IllegalArgumentException.class, () -> new LogPosition(0,1));
        assertThrows(IllegalArgumentException.class, () -> new LogPosition(1,0));
        assertThrows(IllegalArgumentException.class, () -> new LogPosition(-1,1));
        assertThrows(IllegalArgumentException.class, () -> new LogPosition(1,-1));
    }
}
