package io.github.yergaliuly.quorumlab.simulator;

import static org.junit.jupiter.api.Assertions.*;

import io.github.yergaliuly.quorumlab.core.NodeId;
import io.github.yergaliuly.quorumlab.core.OperationId;
import io.github.yergaliuly.quorumlab.core.RaftEvent;
import io.github.yergaliuly.quorumlab.core.TimerKind;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicSchedulerTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");

    @Test
    void emptyQueueDoesNotMoveTheClock() {
        var scheduler = new DeterministicScheduler<String>();
        assertTrue(scheduler.pollNext().isEmpty());
        assertEquals(0, scheduler.nowNanos());
        assertEquals(0, scheduler.pendingEvents());
    }

    @Test
    void chronologicalOrderWinsOverInsertionOrder() {
        var scheduler = new DeterministicScheduler<String>();
        scheduler.scheduleAt(30, A, "last");
        scheduler.scheduleAt(10, B, "first");
        scheduler.scheduleAt(20, A, "middle");
        assertEquals(0, scheduler.nowNanos(), "Scheduling must not advance virtual time");
        assertEquals(3, scheduler.pendingEvents());
        var first = scheduler.pollNext().orElseThrow();
        assertEquals("first", first.payload());
        assertEquals(B, first.target());
        assertEquals(10, scheduler.nowNanos());
        assertEquals("middle", scheduler.pollNext().orElseThrow().payload());
        assertEquals("last", scheduler.pollNext().orElseThrow().payload());
        assertEquals(30, scheduler.nowNanos());
        assertTrue(scheduler.pollNext().isEmpty());
        assertEquals(30, scheduler.nowNanos());
        assertEquals(0, scheduler.pendingEvents());
    }

    @Test
    void equalTimeUsesGlobalInsertionOrderIncludingNewEvents() {
        var scheduler = new DeterministicScheduler<String>();
        scheduler.scheduleAt(10, B, "first");
        scheduler.scheduleAt(10, A, "second");
        assertEquals("first", scheduler.pollNext().orElseThrow().payload());
        scheduler.scheduleAfter(0, B, "third");
        var second = scheduler.pollNext().orElseThrow();
        var third = scheduler.pollNext().orElseThrow();
        assertEquals("second", second.payload());
        assertEquals("third", third.payload());
        assertEquals(1, second.insertionOrder());
        assertEquals(2, third.insertionOrder());
        assertEquals(10, scheduler.nowNanos());
    }

    @Test
    void relativeDelayStartsAtCurrentVirtualTime() {
        var scheduler = new DeterministicScheduler<String>();
        scheduler.scheduleAfter(10, A, "first");
        scheduler.pollNext().orElseThrow();
        scheduler.scheduleAfter(7, A, "second");
        assertEquals(17, scheduler.pollNext().orElseThrow().timeNanos());
        assertEquals(17, scheduler.nowNanos());
    }

    @Test
    void rejectsPastTimeAndNegativeDelayWithoutChangingQueueOrClock() {
        var scheduler = new DeterministicScheduler<String>();
        scheduler.scheduleAt(10, A, "first");
        scheduler.pollNext().orElseThrow();
        scheduler.scheduleAt(20, A, "pending");
        assertThrows(IllegalArgumentException.class, () -> scheduler.scheduleAt(9, A, "past"));
        assertThrows(IllegalArgumentException.class, () -> scheduler.scheduleAt(-1, A, "negative"));
        assertThrows(IllegalArgumentException.class, () -> scheduler.scheduleAfter(-1, A, "negative"));
        assertEquals(10, scheduler.nowNanos());
        assertEquals(1, scheduler.pendingEvents());
        assertEquals("pending", scheduler.pollNext().orElseThrow().payload());
        scheduler.scheduleAt(20, A, "next valid");
        assertEquals(2, scheduler.pollNext().orElseThrow().insertionOrder());
    }

    @Test
    void rejectsMissingTargetOrPayloadWithoutConsumingInsertionOrder() {
        var scheduler = new DeterministicScheduler<String>();
        assertThrows(NullPointerException.class, () -> scheduler.scheduleAt(0, null, "event"));
        assertThrows(NullPointerException.class, () -> scheduler.scheduleAfter(0, A, null));
        assertEquals(0, scheduler.pendingEvents());
        scheduler.scheduleAt(0, A, "valid");
        assertEquals(0, scheduler.pollNext().orElseThrow().insertionOrder());
    }

    @Test
    void overflowingDeadlineFailsWithoutWrappingTimeOrChangingQueue() {
        var scheduler = new DeterministicScheduler<String>();
        scheduler.scheduleAt(Long.MAX_VALUE, A, "far future");
        scheduler.pollNext().orElseThrow();
        assertThrows(ArithmeticException.class, () -> scheduler.scheduleAfter(1, A, "overflow"));
        assertEquals(Long.MAX_VALUE, scheduler.nowNanos());
        assertEquals(0, scheduler.pendingEvents());
        scheduler.scheduleAfter(0, A, "still valid");
        assertEquals(1, scheduler.pollNext().orElseThrow().insertionOrder());
    }

    @Test
    void replayingSameInputsProducesIdenticalTraceOfActualCoreEvents() {
        var first = coreTrace();
        assertEquals(first, coreTrace());
        assertEquals(List.of(5L, 10L, 10L), first.stream().map(e -> e.timeNanos()).toList());
        assertEquals(List.of(B, A, B), first.stream().map(e -> e.target()).toList());
        assertInstanceOf(RaftEvent.PersistCompleted.class, first.getFirst().payload());
        assertEquals(List.of(1L, 0L, 2L), first.stream().map(e -> e.insertionOrder()).toList());
    }

    private List<DeterministicScheduler.ScheduledEvent<RaftEvent>> coreTrace() {
        var scheduler = new DeterministicScheduler<RaftEvent>();
        scheduler.scheduleAt(10, A, new RaftEvent.TimerFired(new OperationId(1, 1), TimerKind.ELECTION));
        scheduler.scheduleAt(5, B, new RaftEvent.PersistCompleted(new OperationId(1, 2)));
        scheduler.scheduleAt(10, B, new RaftEvent.TimerFired(new OperationId(1, 3), TimerKind.HEARTBEAT));
        var trace = new ArrayList<DeterministicScheduler.ScheduledEvent<RaftEvent>>();
        while (scheduler.pendingEvents() > 0) {
            trace.add(scheduler.pollNext().orElseThrow());
        }
        return List.copyOf(trace);
    }
}
