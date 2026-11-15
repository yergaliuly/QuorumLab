package io.github.yergaliuly.quorumlab.storage.wal;

import io.github.yergaliuly.quorumlab.core.Effect;
import io.github.yergaliuly.quorumlab.core.RaftEvent;
import io.github.yergaliuly.quorumlab.core.port.Storage;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

/**
 * Initial metadata-only bridge. Execute on the node's serialized storage worker, then drain the
 * unbounded event queue from its driver. No direct core callback and no ownership of the WAL lifetime.
 */
public final class WalStorage implements Storage {
    private final FileWal wal;
    private final Queue<RaftEvent> events;

    public WalStorage(FileWal wal, Queue<RaftEvent> events) {
        this.wal = Objects.requireNonNull(wal, "wal");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override public void persist(Effect.PersistHardState request) {
        Objects.requireNonNull(request, "request");
        RaftEvent completion;
        try {
            var state = request.state();
            wal.persist(List.of(new WalRecord.SetHardState(state.currentTerm(), state.votedFor()),
                    new WalRecord.SetCommitIndex(state.commitIndex())));
            completion = new RaftEvent.PersistCompleted(request.id());
        } catch (IOException | IllegalArgumentException failure) {
            completion = new RaftEvent.PersistFailed(request.id(), failure.toString());
        }
        // Queue rejection is a driver configuration failure, never a reason to retry a durable write here.
        events.add(completion);
    }
}
