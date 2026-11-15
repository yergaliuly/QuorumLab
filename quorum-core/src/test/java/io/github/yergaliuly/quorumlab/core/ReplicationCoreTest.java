package io.github.yergaliuly.quorumlab.core;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.yergaliuly.quorumlab.core.RaftNodeTest.*;
import static io.github.yergaliuly.quorumlab.core.ReplicationContractsTest.entry;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReplicationCoreTest {
    private static RaftNode follower(long term,long commit,LogEntry... entries) {
        var node=new RaftNode(config(1),new RaftState(new HardState(term,Optional.empty(),commit),List.of(entries)),(a,b)->a);
        node.step(new RaftEvent.Start()); return node;
    }
    private static RaftRpc.AppendEntries append(long term,long sequence,long prev,long prevTerm,long commit,LogEntry... entries) {
        return new RaftRpc.AppendEntries(term,new OperationId(8,sequence),prev,prevTerm,List.of(entries),commit);
    }
    private static RaftRpc.AppendEntries request(List<Effect> effects,NodeId to) {
        return (RaftRpc.AppendEntries)sends(effects).stream().filter(s->s.to().equals(to)).findFirst().orElseThrow().rpc();
    }
    private static List<Effect> ack(RaftNode node,NodeId from,RaftRpc.AppendEntries request,boolean success) {
        return node.step(rpc(from,new RaftRpc.AppendResponse(request.term(),request.requestId(),success,success?request.lastIndex():0)));
    }
    private record Leader(RaftNode node,List<Effect> messages,Effect.ScheduleTimer heartbeat) { }
    private static Leader leader(long term,LogEntry... entries) {
        var node=new RaftNode(new RaftConfig(A,VOTERS,1,100,200,20,1),new RaftState(new HardState(term,Optional.empty(),0),List.of(entries)),(a,b)->a);
        complete(node,begin(node)); var elected=node.step(rpc(B,new RaftRpc.VoteResponse(term+1,true)));
        noSends(elected);
        return new Leader(node,complete(node,one(elected,Effect.PersistBatch.class)),one(elected,Effect.ScheduleTimer.class));
    }
    @Test void appendSuccessAndApplyWaitForMatchingDurableCompletion() {
        var node=follower(1,0); var req=append(1,1,0,0,1,entry(1,1));
        var effects=node.step(rpc(B,req)); noSends(effects);
        assertTrue(effects.stream().noneMatch(Effect.ApplyCommittedEntries.class::isInstance));
        var write=one(effects,Effect.PersistBatch.class);
        assertEquals(0,node.status().durableState().commitIndex());
        assertTrue(node.step(new RaftEvent.PersistCompleted(new OperationId(99,write.id().sequence()))).isEmpty());
        var saved=complete(node,write);
        assertEquals(new RaftRpc.AppendResponse(1,req.requestId(),true,1),one(saved,Effect.SendRpc.class).rpc());
        var apply=one(saved,Effect.ApplyCommittedEntries.class); assertEquals(List.of(entry(1,1)),apply.entries());
        assertEquals(0,node.status().lastApplied());
        node.step(new RaftEvent.ApplyCompleted(apply.id())); assertEquals(1,node.status().lastApplied());
        assertTrue(node.step(new RaftEvent.ApplyCompleted(apply.id())).isEmpty());
    }
    @Test void emptyAppendChecksPrevAndDoesNotCommitUnverifiedExtraSuffix() {
        var node=follower(3,0,entry(1,1),entry(2,2),entry(3,3));
        var mismatch=node.step(rpc(B,append(3,1,3,2,3)));
        assertFalse(((RaftRpc.AppendResponse)one(mismatch,Effect.SendRpc.class).rpc()).success());
        assertEquals(0,node.status().hardState().commitIndex());
        var effects=node.step(rpc(B,append(3,2,1,1,3)));
        assertEquals(1,one(effects,Effect.PersistBatch.class).state().commitIndex());
        assertEquals(3,node.log().size());
    }
    @Test void shortMatchingAppendDoesNotDeleteFollowerSuffix() {
        var node=follower(2,0,entry(1,1),entry(2,2));
        var effects=node.step(rpc(B,append(2,1,0,0,0,entry(1,1))));
        assertEquals(2,node.log().size()); assertTrue(effects.stream().noneMatch(Effect.PersistBatch.class::isInstance));
        assertEquals(1,((RaftRpc.AppendResponse)one(effects,Effect.SendRpc.class).rpc()).matchIndex());
    }
    @Test void conflictReplacesOnlyUncommittedSuffixInOneBatch() {
        var node=follower(3,1,entry(1,1),entry(2,2),entry(3,2));
        var effects=node.step(rpc(B,append(3,1,1,1,2,entry(2,3))));
        var write=one(effects,Effect.PersistBatch.class); noSends(effects);
        assertEquals(2,write.truncateFromIndex()); assertEquals(List.of(entry(2,3)),write.entries());
        assertEquals(List.of(entry(1,1),entry(2,3)),node.log());
        assertEquals(3,node.durableState().log().size());
        complete(node,write); assertEquals(node.log(),node.durableState().log());
    }
    @Test void replacingCommittedEntryFailsClosed() {
        var node=follower(3,2,entry(1,1),entry(2,2));
        assertTrue(node.step(rpc(B,append(3,1,1,1,2,entry(2,3)))).isEmpty());
        assertEquals(RaftNode.Role.FAILED,node.status().role()); assertEquals(entry(2,2),node.log().getLast());
    }
    @Test void sameIndexTermDifferentCommandFailsClosed() {
        var node=follower(1,0,entry(1,1));
        node.step(rpc(B,append(1,1,0,0,0,new LogEntry(1,1,LogEntry.Type.COMMAND,new byte[]{3}))));
        assertEquals(RaftNode.Role.FAILED,node.status().role());
    }
    @Test void duplicateDuringWriteCannotStartAnotherWriteAndGetsOneDurableReply() {
        var node=follower(1,0); var req=append(1,1,0,0,0,entry(1,1));
        var write=one(node.step(rpc(B,req)),Effect.PersistBatch.class);
        var duplicate=node.step(rpc(B,req)); noSends(duplicate);
        assertTrue(duplicate.stream().noneMatch(Effect.PersistBatch.class::isInstance));
        assertTrue(((RaftRpc.AppendResponse)one(complete(node,write),Effect.SendRpc.class).rpc()).success());
        assertTrue(((RaftRpc.AppendResponse)one(node.step(rpc(B,req)),Effect.SendRpc.class).rpc()).success());
    }
    @Test void higherTermDuringAppendCancelsReplyAndPersistsMetadataBeforeRetry() {
        var node=follower(1,0); var first=one(node.step(rpc(B,append(1,1,0,0,0,entry(1,1)))),Effect.PersistBatch.class);
        var newer=append(2,2,0,0,0,entry(1,2)); noSends(node.step(rpc(C,newer)));
        var effects=complete(node,first); noSends(effects);
        var second=one(effects,Effect.PersistBatch.class); assertTrue(second.entries().isEmpty());
        noSends(complete(node,second));
        var repair=one(node.step(rpc(C,newer)),Effect.PersistBatch.class); assertEquals(1,repair.truncateFromIndex());
        assertEquals(C,one(complete(node,repair),Effect.SendRpc.class).to());
    }
    @Test void uncertainAppendFailureReleasesNoReplyOrApply() {
        var node=follower(1,0); var write=one(node.step(rpc(B,append(1,1,0,0,1,entry(1,1)))),Effect.PersistBatch.class);
        assertTrue(node.step(new RaftEvent.PersistFailed(write.id(),"ENOSPC")).isEmpty());
        assertEquals(RaftNode.Role.FAILED,node.status().role()); assertEquals(0,node.status().lastApplied());
        assertTrue(complete(node,write).isEmpty());
    }
    @Test void newLeaderNoopMustPersistBeforeReplicationAndRetryKeepsRequestId() {
        var elected=leader(0); var node=elected.node(); var req=request(elected.messages(),B);
        assertEquals(List.of(entry(1,1)),req.entries()); assertEquals(0,req.leaderCommit());
        assertEquals(req,request(node.step(fire(elected.heartbeat())),B));
        var effects=ack(node,B,req,true); noSends(effects);
        var commit=one(effects,Effect.PersistBatch.class); assertEquals(1,commit.state().commitIndex());
        assertTrue(effects.stream().noneMatch(Effect.ApplyCommittedEntries.class::isInstance));
        var done=complete(node,commit); assertEquals(1,request(done,B).leaderCommit());
        assertEquals(List.of(entry(1,1)),one(done,Effect.ApplyCommittedEntries.class).entries());
    }
    @Test void oldTermMajorityCannotCommitUntilCurrentTermNoop() {
        var elected=leader(3,entry(1,1),entry(2,2)); var node=elected.node();
        var initial=request(elected.messages(),B); assertEquals(2,initial.prevLogIndex());
        var old=request(ack(node,B,initial,false),B); assertEquals(List.of(entry(2,2)),old.entries());
        var next=ack(node,B,old,true); assertEquals(2,node.status().progress().get(B).matchIndex());
        assertEquals(0,node.status().hardState().commitIndex());
        assertTrue(next.stream().noneMatch(Effect.PersistBatch.class::isInstance));
        var noop=request(next,B); assertEquals(List.of(entry(3,4)),noop.entries());
        assertEquals(3,one(ack(node,B,noop,true),Effect.PersistBatch.class).state().commitIndex());
    }
    @Test void staleNegativeAndInflatedSuccessCannotChangeProgress() {
        var elected=leader(3,entry(1,1),entry(2,2)); var node=elected.node(); var initial=request(elected.messages(),B);
        var old=request(ack(node,B,initial,false),B);
        node.step(rpc(B,new RaftRpc.AppendResponse(4,old.requestId(),true,300)));
        assertEquals(0,node.status().progress().get(B).matchIndex());
        ack(node,B,old,true); var before=node.status().progress().get(B);
        assertTrue(ack(node,B,initial,false).isEmpty()); assertEquals(before,node.status().progress().get(B));
        assertTrue(ack(node,B,old,false).isEmpty()); assertEquals(before,node.status().progress().get(B));
    }
    @Test void higherTermStaleResponseStillStepsDown() {
        var elected=leader(0); var node=elected.node();
        var effects=node.step(rpc(B,new RaftRpc.AppendResponse(8,new OperationId(99,99),false,0)));
        noSends(effects); assertEquals(RaftNode.Role.FOLLOWER,node.status().role());
        assertEquals(8,one(effects,Effect.PersistBatch.class).state().currentTerm());
    }
    @Test void proposalAdmissionIsExplicitAndUnsavedCommandIsNotSent() {
        var follower=follower(1,0);
        assertEquals(Effect.ProposalRejected.Reason.NOT_LEADER,one(follower.step(new RaftEvent.Propose(new byte[]{1})),Effect.ProposalRejected.class).reason());
        var elected=leader(0); var node=elected.node();
        var effects=node.step(new RaftEvent.Propose(new byte[]{7})); noSends(effects);
        assertEquals(new Effect.ProposalQueued(2,1),one(effects,Effect.ProposalQueued.class));
        assertEquals(Effect.ProposalRejected.Reason.BUSY,one(node.step(new RaftEvent.Propose(new byte[]{8})),Effect.ProposalRejected.class).reason());
        var write=one(effects,Effect.PersistBatch.class); assertArrayEquals(new byte[]{7},write.entries().getFirst().command());
        node.step(new RaftEvent.PersistFailed(write.id(),"disk"));
        assertEquals(Effect.ProposalRejected.Reason.FAILED,one(node.step(new RaftEvent.Propose(new byte[]{9})),Effect.ProposalRejected.class).reason());
    }
    @Test void recoveredCommittedPrefixAppliesInBoundedOrderedBatches() {
        var entries=new ArrayList<LogEntry>(); for(int i=1;i<=131;i++)entries.add(entry(i,1));
        var node=new RaftNode(config(2),new RaftState(new HardState(1,Optional.empty(),130),entries),(a,b)->a);
        var first=one(node.step(new RaftEvent.Start()),Effect.ApplyCommittedEntries.class); assertEquals(64,first.entries().size());
        assertTrue(node.step(new RaftEvent.ApplyCompleted(new OperationId(1,first.id().sequence()))).isEmpty());
        var second=one(node.step(new RaftEvent.ApplyCompleted(first.id())),Effect.ApplyCommittedEntries.class);
        assertEquals(65,second.entries().getFirst().index()); assertEquals(64,node.status().lastApplied());
        var third=one(node.step(new RaftEvent.ApplyCompleted(second.id())),Effect.ApplyCommittedEntries.class);
        assertEquals(List.of(entry(129,1),entry(130,1)),third.entries());
        assertTrue(node.step(new RaftEvent.ApplyCompleted(third.id())).isEmpty()); assertEquals(130,node.status().lastApplied());
    }
    @Test void applyFailureStopsUntilRecoveryAndOldFailureIsIgnored() {
        var node=new RaftNode(config(2),new RaftState(new HardState(1,Optional.empty(),1),List.of(entry(1,1))),(a,b)->a);
        var apply=one(node.step(new RaftEvent.Start()),Effect.ApplyCommittedEntries.class);
        assertTrue(node.step(new RaftEvent.ApplyFailed(new OperationId(1,1),"old")).isEmpty());
        node.step(new RaftEvent.ApplyFailed(apply.id(),"application error")); assertEquals(RaftNode.Role.FAILED,node.status().role());
        assertEquals(0,node.status().lastApplied());
    }
}
