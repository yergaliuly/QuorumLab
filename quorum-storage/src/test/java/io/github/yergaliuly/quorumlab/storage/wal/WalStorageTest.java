package io.github.yergaliuly.quorumlab.storage.wal;

import static org.junit.jupiter.api.Assertions.*;

import io.github.yergaliuly.quorumlab.core.*;
import java.util.ArrayDeque;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WalStorageTest {
    private static final OperationId ID = new OperationId(7, 2);
    private static final Effect.PersistBatch REQUEST = new Effect.PersistBatch(ID,
            new HardState(1, Optional.of(new NodeId("a")), 0));

    @Test void queuesCompletionWithOriginalIdOnlyAfterDurability() throws Exception {
        var disk = new ModelDisk(); var queue = new ArrayDeque<RaftEvent>();
        try (var wal = FileWal.create(disk, FileWalTest.ID)) {
            disk.observer = ignored -> assertTrue(queue.isEmpty());
            new WalStorage(wal, queue).persist(REQUEST);
            assertEquals(new RaftEvent.PersistCompleted(ID), queue.remove());
            assertTrue(queue.isEmpty());
        }
        disk.crash(false);
        try (var wal = FileWal.open(disk)) { assertEquals(REQUEST.state(), wal.state().hardState()); }
    }

    @Test void queuesFailureInsteadOfSuccessEvenWhenRenameAlreadyHappened() throws Exception {
        var disk = new ModelDisk(); var queue = new ArrayDeque<RaftEvent>();
        try (var wal = FileWal.create(disk, FileWalTest.ID)) {
            disk.fail("directoryForce", 1, false);
            new WalStorage(wal, queue).persist(REQUEST);
            var failure = assertInstanceOf(RaftEvent.PersistFailed.class, queue.remove());
            assertEquals(ID, failure.id()); assertTrue(failure.reason().contains("directoryForce"));
            assertTrue(queue.isEmpty());
        }
    }

    @Test void rejectsCommitBeyondLogWithoutPersistingTermAlone() throws Exception {
        var disk = new ModelDisk(); var queue = new ArrayDeque<RaftEvent>();
        try (var wal = FileWal.create(disk, FileWalTest.ID)) {
            new WalStorage(wal, queue).persist(new Effect.PersistBatch(ID, new HardState(1, Optional.empty(), 1)));
            assertInstanceOf(RaftEvent.PersistFailed.class, queue.remove());
            assertEquals(HardState.initial(), wal.state().hardState());
        }
    }
}
