package io.github.yergaliuly.quorumlab.integration;

import static org.junit.jupiter.api.Assertions.*;
import io.github.yergaliuly.quorumlab.core.*;
import io.github.yergaliuly.quorumlab.storage.wal.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class ReplicationWalIntegrationTest {
    static final NodeId A=new NodeId("a"),B=new NodeId("b"),C=new NodeId("c");
    static final List<NodeId> NODES=List.of(A,B,C);
    @TempDir Path temporary;
    static LogEntry entry(long index,long term,int value) { return new LogEntry(index,term,LogEntry.Type.COMMAND,new byte[]{(byte)value}); }
    static RaftNode core(NodeId self,FileWal wal,long incarnation) {
        return new RaftNode(new RaftConfig(self,NODES,incarnation,100,200,20),wal.state().toRaftState(),(a,b)->a);
    }
    static <T extends Effect> T one(List<Effect> effects,Class<T> type) {
        var matches=effects.stream().filter(type::isInstance).map(type::cast).toList(); assertEquals(1,matches.size(),effects.toString()); return matches.getFirst();
    }
    static void noSendOrApply(List<Effect> effects) {
        assertTrue(effects.stream().noneMatch(e->e instanceof Effect.SendRpc || e instanceof Effect.ApplyCommittedEntries),effects.toString());
    }
    static RaftRpc.AppendEntries request(long term,long prev,long prevTerm,long commit,LogEntry... entries) {
        return new RaftRpc.AppendEntries(term,new OperationId(8,1),prev,prevTerm,List.of(entries),commit);
    }
    static List<Effect> deliver(RaftNode node,RaftRpc request) { return node.step(new RaftEvent.RpcReceived(B,request)); }
    static List<Effect> persist(RaftNode node,FileWal wal,List<Effect> effects) {
        var queue=new ArrayDeque<RaftEvent>(); new WalStorage(wal,queue).persist(one(effects,Effect.PersistBatch.class));
        assertInstanceOf(RaftEvent.PersistCompleted.class,queue.peek()); return node.step(queue.remove());
    }
    static void seed(FileWal wal,long term,LogEntry... entries) throws java.io.IOException {
        var records=new ArrayList<WalRecord>(); records.add(new WalRecord.SetHardState(term,Optional.empty()));
        for(LogEntry entry:entries)records.add(new WalRecord.AppendEntry(entry.index(),entry.term(),WalRecord.EntryType.COMMAND,entry.command()));
        wal.persist(records);
    }

    @Test void durableAppendSurvivesRestartBeforeAckAndOnlyCommittedPrefixReplays() throws Exception {
        Path path=Files.createDirectory(temporary.resolve("node")); OperationId oldId;
        try(var wal=FileWal.create(path)) {
            seed(wal,1); var node=core(A,wal,1); node.step(new RaftEvent.Start());
            var effects=deliver(node,request(1,0,0,1,entry(1,1,11),entry(2,1,22))); noSendOrApply(effects);
            var batch=one(effects,Effect.PersistBatch.class); oldId=batch.id();
            var events=new ArrayDeque<RaftEvent>(); new WalStorage(wal,events).persist(batch);
            assertInstanceOf(RaftEvent.PersistCompleted.class,events.remove());
            assertEquals(2,wal.state().entries().size()); assertEquals(1,wal.state().hardState().commitIndex());
            assertEquals(0,node.status().lastApplied()); // Discard core before delivering completion.
        }
        try(var wal=FileWal.open(path)) {
            var node=core(A,wal,2); var replay=one(node.step(new RaftEvent.Start()),Effect.ApplyCommittedEntries.class);
            assertEquals(List.of(entry(1,1,11)),replay.entries());
            assertTrue(node.step(new RaftEvent.PersistCompleted(oldId)).isEmpty());
            node.step(new RaftEvent.ApplyCompleted(replay.id())); assertEquals(1,node.status().lastApplied());
            assertEquals(2,node.log().size());
        }
    }
    @Test void truncateAppendAndCommitAreRecoveredAsOneCheckpointedBatch() throws Exception {
        Path path=Files.createDirectory(temporary.resolve("node"));
        try(var wal=FileWal.create(path)) {
            seed(wal,3,entry(1,1,1),entry(2,2,2),entry(3,2,3)); wal.persist(List.of(new WalRecord.SetCommitIndex(1)));
            var node=core(A,wal,1); var replay=one(node.step(new RaftEvent.Start()),Effect.ApplyCommittedEntries.class);
            node.step(new RaftEvent.ApplyCompleted(replay.id()));
            var effects=deliver(node,request(3,1,1,2,entry(2,3,9))); noSendOrApply(effects);
            assertEquals(2,one(effects,Effect.PersistBatch.class).truncateFromIndex());
            var saved=persist(node,wal,effects);
            assertTrue(((RaftRpc.AppendResponse)one(saved,Effect.SendRpc.class).rpc()).success());
            assertEquals(List.of(entry(2,3,9)),one(saved,Effect.ApplyCommittedEntries.class).entries());
        }
        try(var wal=FileWal.open(path)) {
            assertEquals(List.of(entry(1,1,1),entry(2,3,9)),wal.state().toRaftState().log());
            assertEquals(2,wal.state().hardState().commitIndex());
        }
    }
    @Test void emptyAppendPersistsCommitBeforeResponseAndApplication() throws Exception {
        Path path=Files.createDirectory(temporary.resolve("node"));
        try(var wal=FileWal.create(path)) {
            seed(wal,1,entry(1,1,7)); var node=core(A,wal,1); node.step(new RaftEvent.Start());
            var effects=deliver(node,request(1,1,1,1)); noSendOrApply(effects);
            assertTrue(one(effects,Effect.PersistBatch.class).entries().isEmpty());
            var saved=persist(node,wal,effects); assertEquals(1,wal.state().hardState().commitIndex());
            assertEquals(List.of(entry(1,1,7)),one(saved,Effect.ApplyCommittedEntries.class).entries());
            assertInstanceOf(RaftRpc.AppendResponse.class,one(saved,Effect.SendRpc.class).rpc());
        }
        try(var wal=FileWal.open(path)) { assertEquals(1,wal.state().hardState().commitIndex()); }
    }
    @Test void invalidTruncationReturnsFailureWithoutChangingCheckpoint() throws Exception {
        Path path=Files.createDirectory(temporary.resolve("node"));
        try(var wal=FileWal.create(path)) {
            seed(wal,1,entry(1,1,7)); wal.persist(List.of(new WalRecord.SetCommitIndex(1)));
            var queue=new ArrayDeque<RaftEvent>(); var before=wal.state().toRaftState();
            new WalStorage(wal,queue).persist(new Effect.PersistBatch(new OperationId(1,1),new HardState(2,Optional.empty(),1),1,List.of(entry(1,2,9))));
            assertInstanceOf(RaftEvent.PersistFailed.class,queue.remove()); assertEquals(before,wal.state().toRaftState());
        }
        try(var wal=FileWal.open(path)) { assertEquals(List.of(entry(1,1,7)),wal.state().toRaftState().log()); }
    }
    @Test void threeRealWalsReplicateCommitApplyAndReplayAfterAllNodesRestart() throws Exception {
        try(var cluster=new Cluster(temporary)) {
            cluster.boot(false); cluster.drain();
            var election=cluster.elections.get(A); cluster.queue.add(new Input(A,new RaftEvent.TimerFired(election.id(),election.kind())));
            cluster.drain(); assertEquals(RaftNode.Role.LEADER,cluster.cores.get(A).status().role());
            for(int value=1;value<=5;value++) {
                cluster.queue.add(new Input(A,new RaftEvent.Propose(new byte[]{(byte)value}))); cluster.drain();
            }
            var applied=List.copyOf(cluster.applied.get(A)); assertEquals(6,applied.size());
            for(NodeId id:NODES)assertEquals(applied,cluster.applied.get(id));
            cluster.close(); cluster.boot(true); cluster.drain();
            for(NodeId id:NODES) {
                assertEquals(applied,cluster.applied.get(id)); assertEquals(6,cluster.cores.get(id).status().lastApplied());
            }
        }
    }
    private record Input(NodeId node,RaftEvent event) { }
    /** In-process event queue with actual disks. It deliberately has no network/runtime claims. */
    private static final class Cluster implements AutoCloseable {
        final Path root; final Queue<Input> queue=new ArrayDeque<>();
        final Map<NodeId,FileWal> wals=new LinkedHashMap<>(); final Map<NodeId,RaftNode> cores=new LinkedHashMap<>();
        final Map<NodeId,List<LogEntry>> applied=new LinkedHashMap<>(); final Map<NodeId,Effect.ScheduleTimer> elections=new HashMap<>();
        Cluster(Path root) { this.root=root; }
        void boot(boolean reopen) throws Exception {
            for(NodeId id:NODES) {
                Path path=root.resolve(id.value()); var wal=reopen?FileWal.open(path):FileWal.create(Files.createDirectory(path));
                wals.put(id,wal); cores.put(id,core(id,wal,reopen?2:1)); applied.put(id,new ArrayList<>());
                queue.add(new Input(id,new RaftEvent.Start()));
            }
        }
        void drain() {
            int budget=1000;
            while(!queue.isEmpty()) {
                assertTrue(--budget>0,"Unexpected event loop"); var input=queue.remove();
                for(Effect effect:cores.get(input.node()).step(input.event()))switch(effect) {
                    case Effect.PersistBatch batch -> {
                        var completion=new ArrayDeque<RaftEvent>(); new WalStorage(wals.get(input.node()),completion).persist(batch);
                        assertInstanceOf(RaftEvent.PersistCompleted.class,completion.peek()); queue.add(new Input(input.node(),completion.remove()));
                    }
                    case Effect.SendRpc send -> queue.add(new Input(send.to(),new RaftEvent.RpcReceived(input.node(),send.rpc())));
                    case Effect.ApplyCommittedEntries apply -> {
                        var output=applied.get(input.node()); assertEquals(output.size()+1L,apply.entries().getFirst().index());
                        assertTrue(apply.entries().getLast().index()<=wals.get(input.node()).state().hardState().commitIndex());
                        output.addAll(apply.entries()); queue.add(new Input(input.node(),new RaftEvent.ApplyCompleted(apply.id())));
                    }
                    case Effect.ScheduleTimer timer -> { if(timer.kind()==TimerKind.ELECTION)elections.put(input.node(),timer); }
                    case Effect.ProposalQueued ignored -> { }
                    case Effect.ProposalRejected rejected -> fail("Rejected proposal: "+rejected);
                }
            }
        }
        @Override public void close() throws Exception { for(var wal:wals.values())wal.close(); wals.clear(); }
    }
}
