package io.github.yergaliuly.quorumlab.simulator;

import io.github.yergaliuly.quorumlab.core.NodeId;
import io.github.yergaliuly.quorumlab.core.port.MonotonicClock;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;

/** Single-threaded queue of immutable payloads ordered by virtual time, then insertion order. */
public final class DeterministicScheduler<E> implements MonotonicClock {
    private final PriorityQueue<ScheduledEvent<E>> events = new PriorityQueue<>(
            Comparator.<ScheduledEvent<E>>comparingLong(ScheduledEvent::timeNanos)
                    .thenComparingLong(ScheduledEvent::insertionOrder));
    private long nowNanos;
    private long nextOrder;

    public record ScheduledEvent<E>(long timeNanos, long insertionOrder, NodeId target, E payload) {
        public ScheduledEvent {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(payload, "payload");
            if (timeNanos < 0 || insertionOrder < 0) {
                throw new IllegalArgumentException("Time and insertion order must be nonnegative");
            }
        }
    }

    /** Adds an event without advancing time. The caller must supply an immutable payload. */
    public void scheduleAt(long timeNanos, NodeId target, E payload) {
        if (timeNanos < nowNanos) {
            throw new IllegalArgumentException("Cannot schedule an event in the past");
        }
        var event = new ScheduledEvent<>(timeNanos, nextOrder, target, payload);
        // Detect exhaustion before mutating the queue; never wrap a tie-breaker to negative.
        long followingOrder = Math.incrementExact(nextOrder);
        events.add(event);
        nextOrder = followingOrder;
    }

    public void scheduleAfter(long delayNanos, NodeId target, E payload) {
        if (delayNanos < 0) {
            throw new IllegalArgumentException("Delay must be nonnegative");
        }
        scheduleAt(Math.addExact(nowNanos, delayNanos), target, payload);
    }

    /** Removes one event and advances time to its deadline; delivery belongs to the driver. */
    public Optional<ScheduledEvent<E>> pollNext() {
        var event = events.poll();
        if (event == null) {
            return Optional.empty();
        }
        nowNanos = event.timeNanos();
        return Optional.of(event);
    }

    @Override
    public long nowNanos() {
        return nowNanos;
    }

    public int pendingEvents() {
        return events.size();
    }
}
