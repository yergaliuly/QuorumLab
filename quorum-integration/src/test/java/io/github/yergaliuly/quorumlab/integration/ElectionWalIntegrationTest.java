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

/** The real storage bridge drives the same core used by the virtual cluster. No Raft/network I/O inside core. */
@EnabledOnOs(OS.LINUX)
class ElectionWalIntegrationTest {
    private static final NodeId A = new NodeId("a"), B = new NodeId("b"), C = new NodeId("c");
    @TempDir Path temporary;

    @Test void durableVoteSurvivesRestartBeforeCompletionAndCannotBeGivenTwice() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("node"));
        var queue = new ArrayDeque<RaftEvent>();
        OperationId previousId;
        try (var wal = FileWal.create(directory)) {
            var core = core(wal, 1); core.step(new RaftEvent.Start());
            var request = new RaftEvent.RpcReceived(B, new RaftRpc.RequestVote(1,0,0));
            var effects = core.step(request); noSends(effects);
            var persist = persist(effects); previousId = persist.id();
            new WalStorage(wal,queue).persist(persist);
            assertEquals(new RaftEvent.PersistCompleted(previousId), queue.peek());
            assertEquals(Optional.of(B), wal.state().hardState().votedFor());
            assertEquals(0, core.status().durableState().currentTerm());
            // Discard the process/core before delivering the already queued durable completion.
        }
        try (var wal = FileWal.open(directory)) {
            var core = core(wal,2); core.step(new RaftEvent.Start());
            assertEquals(new HardState(1,Optional.of(B),0), core.status().hardState());
            assertTrue(core.step(queue.remove()).isEmpty());
            assertEquals(List.of(new Effect.SendRpc(C,new RaftRpc.VoteResponse(1,false))),
                    core.step(new RaftEvent.RpcReceived(C,new RaftRpc.RequestVote(1,0,0))));
            var next = core.step(new RaftEvent.RpcReceived(C,new RaftRpc.RequestVote(2,0,0)));
            noSends(next); var persist = persist(next);
            assertEquals(2,persist.id().incarnation());
            new WalStorage(wal,queue).persist(persist);
            assertTrue(core.step(new RaftEvent.PersistCompleted(previousId)).isEmpty());
            assertEquals(List.of(new Effect.SendRpc(C,new RaftRpc.VoteResponse(2,true))), core.step(queue.remove()));
        }
        try (var wal = FileWal.open(directory)) { assertEquals(new HardState(2,Optional.of(C),0),wal.state().hardState()); }
    }

    @Test void higherTermDuringPendingSelfVoteIsPersistedInOrderThroughRealAdapter() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("node")); var queue = new ArrayDeque<RaftEvent>();
        try (var wal = FileWal.create(directory)) {
            var core = core(wal,1);
            var timer = (Effect.ScheduleTimer)core.step(new RaftEvent.Start()).getFirst();
            var first = persist(core.step(new RaftEvent.TimerFired(timer.id(),timer.kind())));
            noSends(core.step(new RaftEvent.RpcReceived(B,new RaftRpc.RequestVote(3,0,0))));
            var storage = new WalStorage(wal,queue); storage.persist(first);
            var effects = core.step(queue.remove()); noSends(effects);
            var second = persist(effects);
            assertEquals(new HardState(3,Optional.of(B),0),second.state());
            storage.persist(second);
            assertEquals(List.of(new Effect.SendRpc(B,new RaftRpc.VoteResponse(3,true))),core.step(queue.remove()));
        }
        try (var wal = FileWal.open(directory)) { assertEquals(new HardState(3,Optional.of(B),0),wal.state().hardState()); }
    }

    @Test void recoveredLogControlsVoteFreshnessAndCommitMetadataIsPreserved() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("node")); var queue = new ArrayDeque<RaftEvent>();
        try (var wal = FileWal.create(directory)) {
            wal.persist(List.of(new WalRecord.SetHardState(4,Optional.empty()),
                    new WalRecord.AppendEntry(1,3,WalRecord.EntryType.NO_OP,new byte[0]),new WalRecord.SetCommitIndex(1)));
        }
        try (var wal = FileWal.open(directory)) {
            var core = core(wal,1); core.step(new RaftEvent.Start()); var storage = new WalStorage(wal,queue);
            var effects = core.step(new RaftEvent.RpcReceived(B,new RaftRpc.RequestVote(5,100,2))); noSends(effects);
            storage.persist(persist(effects));
            assertEquals(List.of(new Effect.SendRpc(B,new RaftRpc.VoteResponse(5,false))),core.step(queue.remove()));
            effects = core.step(new RaftEvent.RpcReceived(C,new RaftRpc.RequestVote(5,1,3))); noSends(effects);
            storage.persist(persist(effects));
            assertEquals(List.of(new Effect.SendRpc(C,new RaftRpc.VoteResponse(5,true))),core.step(queue.remove()));
            assertEquals(1,wal.state().hardState().commitIndex()); assertEquals(1,wal.state().entries().size());
        }
    }

    @Test void integrationDataUsesBuildFilesystem() throws Exception {
        assertTrue(temporary.toAbsolutePath().normalize().startsWith(Path.of("build/tmp/test-files").toAbsolutePath().normalize()));
        String type=Files.getFileStore(temporary).type(); assertNotEquals("tmpfs",type); assertNotEquals("ramfs",type);
        System.out.println("Election integration filesystem: " + type + "; data: " + temporary);
    }

    private static ElectionCore core(FileWal wal,long incarnation) {
        var state=wal.state();
        LogPosition last=state.entries().isEmpty()? LogPosition.empty()
                :new LogPosition(state.entries().getLast().index(),state.entries().getLast().term());
        return new ElectionCore(new ElectionConfig(A,List.of(A,B,C),incarnation,100,200,20),state.hardState(),last,(a,b)->a);
    }
    private static Effect.PersistHardState persist(List<Effect> effects) {
        var matches=effects.stream().filter(Effect.PersistHardState.class::isInstance).map(Effect.PersistHardState.class::cast).toList();
        assertEquals(1,matches.size(),effects.toString()); return matches.getFirst();
    }
    private static void noSends(List<Effect> effects) { assertTrue(effects.stream().noneMatch(Effect.SendRpc.class::isInstance),effects.toString()); }
}
