package io.github.yergaliuly.quorumlab.simulator;

import static org.junit.jupiter.api.Assertions.*;
import io.github.yergaliuly.quorumlab.core.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class ReplicationSimulationTest {
    static final NodeId A=new NodeId("a"),B=new NodeId("b"),C=new NodeId("c");
    static final List<NodeId> NODES=List.of(A,B,C);
    static final RaftSimulation.Timing TIMING=new RaftSimulation.Timing(100,200,20,5,10,5);
    static void until(RaftSimulation sim,BooleanSupplier condition) {
        for(int i=0;i<4000 && !condition.getAsBoolean();i++)assertTrue(sim.step());
        assertTrue(condition.getAsBoolean(),()->String.join("\n",sim.trace().subList(Math.max(0,sim.trace().size()-20),sim.trace().size())));
    }
    static LogEntry entry(long index,long term,int value) { return new LogEntry(index,term,LogEntry.Type.COMMAND,new byte[]{(byte)value}); }
    static RaftState state(LogEntry... entries) { return new RaftState(new HardState(3,Optional.empty(),0),List.of(entries)); }
    static NodeId stable(RaftSimulation sim) { sim.runSteps(500); assertEquals(1,sim.leaders().size()); return sim.leaders().getFirst(); }
    static LogPosition propose(RaftSimulation sim,NodeId leader,byte[] command) {
        until(sim,()->sim.status(leader).pendingPersist().isEmpty());
        return sim.propose(leader,command).orElseThrow();
    }

    @Test void replicatesAndAppliesCommandsInOrderOnAllNodes() {
        var sim=new RaftSimulation(NODES,42,TIMING); NodeId leader=stable(sim);
        for(int i=0;i<12;i++) {
            var position=propose(sim,leader,new byte[]{(byte)i});
            until(sim,()->NODES.stream().allMatch(id->sim.status(id).lastApplied()>=position.index()));
        }
        for(NodeId id:NODES)assertEquals(sim.applied(leader),sim.applied(id));
        assertEquals(12,sim.applied(leader).stream().filter(e->e.type()==LogEntry.Type.COMMAND).count());
    }
    @Test void figure8OldTermMajorityIsNotCommitAndCanBeReplaced() {
        LogEntry first=entry(1,1,1),old=entry(2,2,2),newer=entry(2,3,3);
        var sim=new RaftSimulation(NODES,42,TIMING,Map.of(A,state(first,old),B,state(first),C,state(first,newer)),1);
        sim.isolate(C);
        until(sim,()->sim.leaders().contains(A) && sim.status(A).progress().get(B).matchIndex()==2);
        assertEquals(old,sim.durableLog(B).getLast());
        assertEquals(0,sim.status(A).hardState().commitIndex());
        assertTrue(sim.applied(A).isEmpty());
        // Drop the already scheduled NO_OP delivery too; reconnect only B and C.
        sim.isolate(A); sim.crash(A,false); sim.setLink(B,C,true); sim.setLink(C,B,true);
        until(sim,()->sim.leaders().contains(C) && sim.status(B).lastApplied()>=3);
        assertEquals(newer,sim.durableLog(B).get(1)); assertEquals(newer,sim.applied(B).get(1));
        assertFalse(sim.applied(B).contains(old));
        sim.restart(A); sim.heal(); sim.runSteps(1500);
        assertEquals(sim.durableLog(C),sim.durableLog(A)); assertEquals(sim.applied(C),sim.applied(A));
    }
    @Test void isolatedOldLeaderLosesUncommittedSuffixAndKeepsCommittedPrefix() {
        var sim=new RaftSimulation(NODES,21,TIMING); NodeId old=stable(sim);
        var committed=propose(sim,old,new byte[]{1}); until(sim,()->NODES.stream().allMatch(id->sim.status(id).lastApplied()>=committed.index()));
        var prefix=sim.applied(old); sim.isolate(old);
        var lost=propose(sim,old,new byte[]{2}); sim.runSteps(800);
        assertTrue(sim.status(old).hardState().commitIndex()<lost.index());
        NodeId newer=sim.leaders().stream().filter(id->!id.equals(old)).findFirst().orElseThrow();
        var kept=propose(sim,newer,new byte[]{3});
        until(sim,()->sim.status(newer).lastApplied()>=kept.index());
        sim.heal(); sim.runSteps(1500);
        for(NodeId id:NODES) {
            assertEquals(prefix,sim.applied(id).subList(0,prefix.size()));
            assertEquals(sim.applied(newer),sim.applied(id));
            assertFalse(sim.applied(id).stream().anyMatch(e->Arrays.equals(new byte[]{2},e.command())));
        }
    }
    @Test void slowRoundTripRetriesWithoutChangingRequestAndEventuallyCommits() {
        var sim=new RaftSimulation(NODES,17,new RaftSimulation.Timing(1000,2000,20,70,30,10,90));
        NodeId leader=stable(sim); var position=propose(sim,leader,new byte[]{7});
        until(sim,()->NODES.stream().allMatch(id->sim.status(id).lastApplied()>=position.index()));
        assertEquals(1,sim.leadersByTerm().size());
    }
    @Test void restartReplaysOnlyCommittedPrefixWithoutDuplicateVolatileApply() {
        var sim=new RaftSimulation(NODES,9,TIMING); NodeId leader=stable(sim);
        var position=propose(sim,leader,new byte[]{9}); until(sim,()->NODES.stream().allMatch(id->sim.status(id).lastApplied()>=position.index()));
        NodeId follower=NODES.stream().filter(id->!id.equals(leader)).findFirst().orElseThrow();
        var before=sim.applied(follower); sim.crash(follower,false); sim.restart(follower);
        assertTrue(sim.applied(follower).isEmpty());
        until(sim,()->sim.status(follower).lastApplied()==before.size()); assertEquals(before,sim.applied(follower));
    }
    @Test void seededCommandsPartitionsCrashesAndWriteFailuresPreserveSafety() {
        for(long seed=0;seed<100;seed++) {
            var sim=new RaftSimulation(NODES,seed,TIMING); var rng=new Random(seed^0x8452L);
            for(int action=0;action<50;action++) {
                NodeId id=NODES.get(rng.nextInt(3));
                switch(rng.nextInt(7)) {
                    case 0 -> sim.isolate(id);
                    case 1 -> sim.heal();
                    case 2 -> { if(sim.running(id))sim.crash(id,rng.nextBoolean()); }
                    case 3 -> { if(!sim.running(id)||sim.status(id).role()==RaftNode.Role.FAILED)sim.restart(id); }
                    case 4,5 -> { if(sim.running(id))sim.propose(id,new byte[]{(byte)action,(byte)seed}); }
                    case 6 -> { if(sim.running(id))sim.failNextPersist(id,rng.nextBoolean()); }
                }
                sim.runSteps(10+rng.nextInt(30));
            }
            sim.heal();
            for(NodeId id:NODES) {
                if(sim.running(id))sim.crash(id,true);
                sim.restart(id);
            }
            NodeId leader=stable(sim); var last=propose(sim,leader,new byte[]{99});
            until(sim,()->NODES.stream().allMatch(id->sim.status(id).lastApplied()>=last.index()));
            for(NodeId id:NODES)assertEquals(sim.applied(leader),sim.applied(id),"seed="+seed);
        }
    }
}
