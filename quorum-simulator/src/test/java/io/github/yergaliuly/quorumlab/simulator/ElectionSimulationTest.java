package io.github.yergaliuly.quorumlab.simulator;

import static org.junit.jupiter.api.Assertions.*;

import io.github.yergaliuly.quorumlab.core.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ElectionSimulationTest {
    private static final NodeId A = new NodeId("a"), B = new NodeId("b"), C = new NodeId("c");
    private static final List<NodeId> NODES = List.of(A,B,C);
    private static final ElectionSimulation.Timing TIMING = new ElectionSimulation.Timing(100,200,20,5,10,5);
    private static ElectionSimulation simulation(long seed) { return new ElectionSimulation(NODES, seed, TIMING); }
    private static void until(ElectionSimulation sim, BooleanSupplier condition) {
        for (int i=0; i<500 && !condition.getAsBoolean(); i++) assertTrue(sim.step());
        assertTrue(condition.getAsBoolean(), () -> String.join("\n", sim.trace().subList(Math.max(0, sim.trace().size()-15), sim.trace().size())));
    }

    @Test void electsOneStableLeaderAndHeartbeatsPreventFurtherTerms() {
        var sim = simulation(42); until(sim, () -> sim.leaders().size() == 1);
        NodeId leader = sim.leaders().getFirst(); long term = sim.status(leader).hardState().currentTerm();
        sim.runSteps(1000);
        assertEquals(List.of(leader), sim.leaders());
        assertEquals(term, sim.status(leader).hardState().currentTerm());
        for (NodeId id : NODES) assertEquals(term, sim.status(id).hardState().currentTerm());
    }

    @Test void splitSelfVotesResolveInLaterTermAfterNetworkHeals() {
        var sim = simulation(3); NODES.forEach(sim::isolate);
        until(sim, () -> NODES.stream().allMatch(id -> sim.status(id).role() == ElectionCore.Role.CANDIDATE));
        assertTrue(sim.leadersByTerm().isEmpty());
        for (NodeId id : NODES) assertEquals(1, sim.status(id).hardState().currentTerm());
        sim.heal(); sim.runSteps(500);
        assertEquals(1, sim.leaders().size());
        assertTrue(sim.status(sim.leaders().getFirst()).hardState().currentTerm() >= 2);
    }

    @Test void isolatedLeaderAndMajorityLeaderMayCoexistInDifferentTermsThenConverge() {
        var sim = simulation(21); sim.runSteps(300); NodeId old = sim.leaders().getFirst();
        long oldTerm = sim.status(old).hardState().currentTerm(); sim.isolate(old); sim.runSteps(800);
        assertEquals(2, sim.leaders().size());
        NodeId newer = sim.leaders().stream().filter(id -> !id.equals(old)).findFirst().orElseThrow();
        assertTrue(sim.status(newer).hardState().currentTerm() > oldTerm);
        assertEquals(oldTerm, sim.status(old).hardState().currentTerm());
        sim.heal(); sim.runSteps(500);
        assertEquals(List.of(newer), sim.leaders());
        assertEquals(ElectionCore.Role.FOLLOWER, sim.status(old).role());
    }

    @Test void leaderCrashAllowsRemainingMajorityAndRestartedNodeJoins() {
        var sim = simulation(9); sim.runSteps(300); NodeId old = sim.leaders().getFirst();
        sim.crash(old, false); sim.runSteps(500);
        assertEquals(1, sim.leaders().size()); assertNotEquals(old, sim.leaders().getFirst());
        NodeId newer = sim.leaders().getFirst(); sim.restart(old); sim.runSteps(500);
        assertEquals(List.of(newer), sim.leaders());
        assertEquals(sim.durableState(newer).currentTerm(), sim.durableState(old).currentTerm());
    }

    @Test void oneSurvivingNodeCannotElectItself() {
        var sim = simulation(7); sim.crash(B, false); sim.crash(C, false); sim.runSteps(300);
        assertTrue(sim.leaders().isEmpty()); assertTrue(sim.leadersByTerm().isEmpty());
        assertEquals(ElectionCore.Role.CANDIDATE, sim.status(A).role());
        assertEquals(Set.of(A), sim.status(A).votes());
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void pendingWriteHasExplicitCrashOutcomeAndOldCompletionCannotAcknowledgeRestart(boolean persistPending) {
        var sim = simulation(42);
        until(sim, () -> NODES.stream().anyMatch(id -> sim.status(id).pendingPersist().isPresent()));
        NodeId node = NODES.stream().filter(id -> sim.status(id).pendingPersist().isPresent()).findFirst().orElseThrow();
        var before = sim.durableState(node); var wanted = sim.status(node).hardState();
        var oldId = sim.status(node).pendingPersist().orElseThrow();
        sim.crash(node, persistPending); sim.restart(node);
        assertEquals(persistPending ? wanted : before, sim.status(node).hardState());
        sim.injectEvent(node, new RaftEvent.PersistCompleted(oldId), 0);
        sim.injectEvent(node, new RaftEvent.PersistFailed(oldId, "stale"), 0);
        sim.runSteps(500); assertNotEquals(ElectionCore.Role.FAILED, sim.status(node).role());
        assertEquals(1, sim.leaders().size());
    }

    @Test void durableWriteAndCompletionAreSeparateEvents() {
        var sim = new ElectionSimulation(NODES, 5, new ElectionSimulation.Timing(100,200,20,5,10,500));
        until(sim, () -> NODES.stream().anyMatch(id -> sim.durableState(id).currentTerm() > sim.status(id).durableState().currentTerm()));
        NodeId node = NODES.stream().filter(id -> sim.durableState(id).currentTerm() > sim.status(id).durableState().currentTerm()).findFirst().orElseThrow();
        HardState written = sim.durableState(node); sim.crash(node, false); sim.restart(node);
        assertEquals(written, sim.status(node).hardState());
    }

    @ParameterizedTest @ValueSource(booleans = {false,true})
    void unknownFailureOutcomeStopsNodeUntilRecovery(boolean written) {
        var sim = simulation(42); sim.failNextPersist(A, written);
        until(sim, () -> sim.status(A).role() == ElectionCore.Role.FAILED);
        assertEquals(written, sim.durableState(A).currentTerm() > 0);
        HardState saved = sim.durableState(A); sim.restart(A); assertEquals(saved, sim.status(A).hardState());
        sim.runSteps(500); assertEquals(1, sim.leaders().size());
    }

    @Test void fixedSeedAndFaultScriptProduceIdenticalTraces() {
        var first = simulation(2026); var second = simulation(2026);
        for (var sim : List.of(first, second)) {
            sim.runSteps(200); sim.isolate(A); sim.runSteps(100); sim.crash(B, true);
            sim.runSteps(30); sim.restart(B); sim.heal(); sim.runSteps(300);
            // Delayed/duplicate obsolete RPCs carry valid sender identity but cannot count in a new term.
            sim.injectRpc(B,A,new RaftRpc.VoteResponse(0,false),3);
            sim.injectRpc(B,A,new RaftRpc.VoteResponse(0,false),7); sim.runSteps(100);
        }
        assertEquals(first.trace(), second.trace()); assertEquals(first.leadersByTerm(), second.leadersByTerm());
        assertThrows(UnsupportedOperationException.class, () -> first.trace().clear());
    }

    @Test void seededFaultSmokeMaintainsSafetyAndEventuallyRecoversInStableConditions() {
        for (long seed=0; seed<100; seed++) {
            var sim = simulation(seed); var random = new Random(seed ^ 0x2ab4L);
            for (int action=0; action<35; action++) {
                NodeId node = NODES.get(random.nextInt(3));
                switch (random.nextInt(5)) {
                    case 0 -> sim.isolate(node);
                    case 1 -> sim.heal();
                    case 2 -> { if (sim.running(node)) sim.crash(node, random.nextBoolean()); }
                    case 3 -> { if (!sim.running(node)) sim.restart(node); }
                    case 4 -> sim.injectRpc(node, NODES.get((NODES.indexOf(node)+1)%3), new RaftRpc.VoteResponse(0,false), random.nextInt(10));
                }
                sim.runSteps(10 + random.nextInt(20));
            }
            sim.heal(); for (NodeId node : NODES) if (!sim.running(node)) sim.restart(node);
            sim.runSteps(1000);
            assertEquals(1, sim.leaders().size(), "seed=" + seed);
            assertFalse(sim.leadersByTerm().isEmpty(), "seed=" + seed);
        }
    }

    @Test void seededRandomIsRepeatableAndRespectsBounds() {
        var first = new SeededRandomSource(10); var second = new SeededRandomSource(10);
        var values = new HashSet<Long>();
        for(int i=0;i<100;i++) {
            long value=first.nextLong(100,200); assertEquals(value,second.nextLong(100,200));
            assertTrue(value>=100 && value<200); values.add(value);
        }
        assertTrue(values.size()>1);
        assertThrows(IllegalArgumentException.class, () -> first.nextLong(5,5));
    }

    @Test void rejectsInvalidTimingUnknownNodeAndRestartOfLiveNode() {
        assertThrows(IllegalArgumentException.class, () -> new ElectionSimulation.Timing(10,20,5,-1,0,0));
        var sim = simulation(1);
        assertThrows(IllegalArgumentException.class, () -> sim.status(new NodeId("x")));
        assertThrows(IllegalStateException.class, () -> sim.restart(A));
        assertThrows(IllegalArgumentException.class, () -> sim.runSteps(-1));
    }
}
