package io.github.yergaliuly.quorumlab.simulator;

import io.github.yergaliuly.quorumlab.core.*;
import java.util.*;

/** Minimal, single-threaded three-node election lab. No sockets, files, sleeping or full fault-engine API. */
public final class ElectionSimulation {
    public record Timing(long minElectionNanos, long maxElectionNanos, long heartbeatNanos,
                         long rpcDelayNanos, long writeDelayNanos, long completionDelayNanos) {
        public Timing {
            if (heartbeatNanos <= 0 || minElectionNanos <= heartbeatNanos || maxElectionNanos <= minElectionNanos
                    || rpcDelayNanos < 0 || writeDelayNanos < 0 || completionDelayNanos < 0) {
                throw new IllegalArgumentException("Invalid election simulation timing");
            }
        }
    }
    private sealed interface Delivery {
        record Input(long incarnation, RaftEvent event) implements Delivery { }
        record Rpc(NodeId from, RaftRpc message) implements Delivery { }
        record Write(long incarnation, Effect.PersistHardState request) implements Delivery { }
    }
    private record Link(NodeId from, NodeId to) { }
    private enum DiskFailure { NONE, BEFORE_WRITE, AFTER_WRITE }
    private static final class Node {
        final NodeId id;
        long incarnation;
        boolean running;
        ElectionCore core;
        HardState durable = HardState.initial();
        Effect.PersistHardState pending;
        DiskFailure nextFailure = DiskFailure.NONE;
        Node(NodeId id) { this.id = id; }
    }

    private final List<NodeId> voters;
    private final Timing timing;
    private final Random seeds;
    private final Map<NodeId, Node> nodes = new LinkedHashMap<>();
    private final Set<Link> blocked = new HashSet<>();
    private final DeterministicScheduler<Delivery> scheduler = new DeterministicScheduler<>();
    private final List<String> trace = new ArrayList<>();
    private final Map<Long, NodeId> elected = new TreeMap<>();

    public ElectionSimulation(List<NodeId> voters, long seed, Timing timing) {
        this.voters = List.copyOf(voters).stream().sorted(Comparator.comparing(NodeId::value)).toList();
        if (this.voters.size() != 3 || new HashSet<>(this.voters).size() != 3) {
            throw new IllegalArgumentException("Three distinct voters required");
        }
        this.timing = Objects.requireNonNull(timing, "timing");
        this.seeds = new Random(seed);
        log(null, "seed=" + seed + " timing=" + timing);
        for (NodeId id : this.voters) nodes.put(id, new Node(id));
        for (Node node : nodes.values()) boot(node);
    }

    public boolean step() {
        var next = scheduler.pollNext();
        if (next.isEmpty()) return false;
        var event = next.get(); Node node = node(event.target());
        log(node.id, "delivery=" + event.payload());
        if (!node.running) { log(node.id, "drop: crashed"); return true; }
        switch (event.payload()) {
            case Delivery.Input input -> {
                if (input.incarnation() == node.incarnation) deliver(node, input.event());
                else log(node.id, "drop: old incarnation");
            }
            case Delivery.Rpc rpc -> {
                if (!blocked.contains(new Link(rpc.from(), node.id))) deliver(node, new RaftEvent.RpcReceived(rpc.from(), rpc.message()));
                else log(node.id, "drop: partition");
            }
            case Delivery.Write write -> {
                if (write.incarnation() != node.incarnation || !write.request().equals(node.pending)) {
                    log(node.id, "drop: old write"); break;
                }
                var failure = node.nextFailure; node.nextFailure = DiskFailure.NONE;
                if (failure != DiskFailure.BEFORE_WRITE) save(node, write.request().state());
                node.pending = null;
                RaftEvent completion = failure == DiskFailure.NONE
                        ? new RaftEvent.PersistCompleted(write.request().id())
                        : new RaftEvent.PersistFailed(write.request().id(), "Injected disk failure: " + failure);
                scheduler.scheduleAfter(timing.completionDelayNanos(), node.id, new Delivery.Input(node.incarnation, completion));
            }
        }
        checkSafety();
        return true;
    }

    public void runSteps(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative event budget");
        for (int i=0; i<count; i++) if (!step()) break;
    }
    public ElectionCore.Status status(NodeId id) { return node(id).core.status(); }
    public HardState durableState(NodeId id) { return node(id).durable; }
    public boolean running(NodeId id) { return node(id).running; }
    public List<NodeId> leaders() {
        return nodes.values().stream().filter(node -> node.running && node.core.status().role() == ElectionCore.Role.LEADER).map(node -> node.id).toList();
    }
    public Map<Long, NodeId> leadersByTerm() { return Collections.unmodifiableMap(new TreeMap<>(elected)); }
    public List<String> trace() { return List.copyOf(trace); }

    public void isolate(NodeId id) {
        node(id);
        for (NodeId other : voters) if (!other.equals(id)) {
            blocked.add(new Link(id, other)); blocked.add(new Link(other, id));
        }
        log(id, "isolate");
    }
    public void heal() { blocked.clear(); log(null, "heal network"); }

    /** Explicit model choice for an unfinished write. Already durable writes are always retained. */
    public void crash(NodeId id, boolean persistPending) {
        Node node = node(id);
        if (!node.running) throw new IllegalStateException("Node already crashed");
        log(id, "crash persistPending=" + persistPending);
        if (persistPending && node.pending != null) save(node, node.pending.state());
        node.pending = null; node.running = false; node.nextFailure = DiskFailure.NONE;
    }
    public void restart(NodeId id) {
        Node node = node(id);
        if (node.running && node.core.status().role() != ElectionCore.Role.FAILED) throw new IllegalStateException("Node is still running");
        boot(node);
    }
    public void failNextPersist(NodeId id, boolean writeBeforeFailure) {
        node(id).nextFailure = writeBeforeFailure ? DiskFailure.AFTER_WRITE : DiskFailure.BEFORE_WRITE;
        log(id, "fail next persist afterWrite=" + writeBeforeFailure);
    }

    /** Test injection must respect the non-Byzantine model (e.g. duplicates of real/obsolete messages). */
    public void injectRpc(NodeId from, NodeId to, RaftRpc rpc, long delayNanos) {
        node(from); node(to); Objects.requireNonNull(rpc, "rpc");
        scheduler.scheduleAfter(delayNanos, to, new Delivery.Rpc(from, rpc));
        log(to, "inject RPC from=" + from + " message=" + rpc + " delay=" + delayNanos);
    }
    public void injectEvent(NodeId to, RaftEvent event, long delayNanos) {
        Node node = node(to); Objects.requireNonNull(event, "event");
        scheduler.scheduleAfter(delayNanos, to, new Delivery.Input(node.incarnation, event));
        log(to, "inject=" + event + " delay=" + delayNanos);
    }

    private void boot(Node node) {
        node.incarnation = Math.incrementExact(node.incarnation); node.running = true; node.pending = null; node.nextFailure = DiskFailure.NONE;
        long seed = seeds.nextLong();
        var config = new ElectionConfig(node.id, voters, node.incarnation, timing.minElectionNanos(), timing.maxElectionNanos(), timing.heartbeatNanos());
        node.core = new ElectionCore(config, node.durable, LogPosition.empty(), new SeededRandomSource(seed));
        log(node.id, "boot incarnation=" + node.incarnation + " randomSeed=" + seed + " recovered=" + node.durable);
        deliver(node, new RaftEvent.Start());
    }

    private void deliver(Node node, RaftEvent event) {
        for (Effect effect : node.core.step(event)) {
            log(node.id, "effect=" + effect);
            switch (effect) {
                case Effect.ScheduleTimer timer -> scheduler.scheduleAfter(timer.delayNanos(), node.id,
                        new Delivery.Input(node.incarnation, new RaftEvent.TimerFired(timer.id(), timer.kind())));
                case Effect.PersistHardState persist -> {
                    if (node.pending != null) throw new AssertionError("Concurrent storage writes at " + node.id);
                    node.pending = persist;
                    scheduler.scheduleAfter(timing.writeDelayNanos(), node.id, new Delivery.Write(node.incarnation, persist));
                }
                case Effect.SendRpc send -> {
                    checkDurableSend(node, send);
                    if (!blocked.contains(new Link(node.id, send.to()))) scheduler.scheduleAfter(timing.rpcDelayNanos(), send.to(), new Delivery.Rpc(node.id, send.rpc()));
                    else log(node.id, "drop outgoing to=" + send.to());
                }
            }
        }
        checkSafety();
    }

    private void checkDurableSend(Node node, Effect.SendRpc send) {
        long term = switch (send.rpc()) {
            case RaftRpc.RequestVote request -> request.term();
            case RaftRpc.VoteResponse response -> response.term();
            case RaftRpc.Heartbeat heartbeat -> heartbeat.term();
        };
        if (term != node.durable.currentTerm()) throw new AssertionError("RPC before durable term: " + send);
        NodeId requiredVote = switch (send.rpc()) {
            case RaftRpc.RequestVote ignored -> node.id;
            case RaftRpc.Heartbeat ignored -> node.id;
            case RaftRpc.VoteResponse response -> response.granted() ? send.to() : null;
        };
        if (requiredVote != null && !node.durable.votedFor().equals(Optional.of(requiredVote))) {
            throw new AssertionError("RPC before durable vote: " + send);
        }
    }

    private void save(Node node, HardState state) {
        if (state.currentTerm() < node.durable.currentTerm() || state.commitIndex() != node.durable.commitIndex()
                || (state.currentTerm() == node.durable.currentTerm() && node.durable.votedFor().isPresent()
                    && !state.votedFor().equals(node.durable.votedFor()))) {
            throw new AssertionError("Invalid persisted metadata transition: " + node.durable + " -> " + state);
        }
        node.durable = state; log(node.id, "durable=" + state);
    }

    private void checkSafety() {
        for (Node node : nodes.values()) {
            if (!node.running || node.core.status().role() != ElectionCore.Role.LEADER) continue;
            var status = node.core.status();
            if (status.votes().size() < 2 || !status.hardState().equals(node.durable)) throw new AssertionError("Invalid leader at " + node.id);
            NodeId previous = elected.putIfAbsent(status.hardState().currentTerm(), node.id);
            if (previous != null && !previous.equals(node.id)) {
                throw new AssertionError("Two leaders in term " + status.hardState().currentTerm() + ": " + previous + ", " + node.id);
            }
        }
    }

    private Node node(NodeId id) {
        Node node = nodes.get(Objects.requireNonNull(id, "id"));
        if (node == null) throw new IllegalArgumentException("Unknown node: " + id);
        return node;
    }
    private void log(NodeId id, String action) { trace.add(scheduler.nowNanos() + " " + (id == null ? "*" : id.value()) + " " + action); }
}
