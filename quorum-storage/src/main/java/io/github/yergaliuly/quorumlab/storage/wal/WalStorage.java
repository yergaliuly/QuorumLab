package io.github.yergaliuly.quorumlab.storage.wal;

import io.github.yergaliuly.quorumlab.core.Effect;
import io.github.yergaliuly.quorumlab.core.RaftEvent;
import io.github.yergaliuly.quorumlab.core.port.Storage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Queue;

/**
 * Atomic metadata/log/commit bridge. Execute on the node's serialized storage worker, then drain the
 * unbounded event queue from its driver. No direct core callback and no ownership of the WAL lifetime.
 */
public final class WalStorage implements Storage {
    private final FileWal wal;
    private final Queue<RaftEvent> events;

    public WalStorage(FileWal wal, Queue<RaftEvent> events) {
        this.wal = Objects.requireNonNull(wal, "wal");
        this.events = Objects.requireNonNull(events, "events");
    }

    @Override public void persist(Effect.PersistBatch request) {
        Objects.requireNonNull(request, "request");
        RaftEvent completion;
        try {
            var state = request.state();
            var records=new ArrayList<WalRecord>();
            records.add(new WalRecord.SetHardState(state.currentTerm(),state.votedFor()));
            if(request.truncateFromIndex()>0) records.add(new WalRecord.TruncateSuffix(request.truncateFromIndex()));
            for(var entry:request.entries()) records.add(new WalRecord.AppendEntry(entry.index(),entry.term(),
                    WalRecord.EntryType.valueOf(entry.type().name()),entry.command()));
            records.add(new WalRecord.SetCommitIndex(state.commitIndex()));
            wal.persist(records);
            completion = new RaftEvent.PersistCompleted(request.id());
        } catch (IOException | IllegalArgumentException failure) {
            completion = new RaftEvent.PersistFailed(request.id(), failure.toString());
        }
        // Queue rejection is a driver configuration failure, never a reason to retry a durable write here.
        events.add(completion);
    }
}
