package io.github.yergaliuly.quorumlab.simulator;

import io.github.yergaliuly.quorumlab.core.*;
import java.util.*;

/** Three-node deterministic lab with independent durable-log and applied-prefix safety checks. */
public final class RaftSimulation {
    public record Timing(long minElectionNanos, long maxElectionNanos, long heartbeatNanos,
                         long rpcDelayNanos, long writeDelayNanos, long completionDelayNanos, long applyDelayNanos) {
        public Timing(long min,long max,long heartbeat,long rpc,long write,long completion) {
            this(min,max,heartbeat,rpc,write,completion,1);
        }
        public Timing {
            if (heartbeatNanos <= 0 || minElectionNanos <= heartbeatNanos || maxElectionNanos <= minElectionNanos
                    || rpcDelayNanos < 0 || writeDelayNanos < 0 || completionDelayNanos < 0 || applyDelayNanos<0) {
                throw new IllegalArgumentException("Invalid election simulation timing");
            }
        }
    }
    private sealed interface Delivery {
        record Input(long incarnation, RaftEvent event) implements Delivery { }
        record Rpc(NodeId from, RaftRpc message) implements Delivery { }
        record Write(long incarnation, Effect.PersistBatch request) implements Delivery { }
        record Apply(long incarnation, Effect.ApplyCommittedEntries request) implements Delivery { }
    }
    private record Link(NodeId from, NodeId to) { }
    private record RequestKey(NodeId leader,OperationId id) { }
    private enum DiskFailure { NONE, BEFORE_WRITE, AFTER_WRITE }
    private static final class Node {
        final NodeId id;
        long incarnation;
        boolean running;
        RaftNode core;
        RaftState durable = RaftState.initial();
        final List<LogEntry> applied=new ArrayList<>();
        Effect.PersistBatch pending;
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
    private final Map<Long,LogEntry> committed=new TreeMap<>();
    private final Map<Long,LogEntry> appliedHistory=new TreeMap<>();
    private final Map<RequestKey,RaftRpc.AppendEntries> requests=new HashMap<>();
    private final int maxAppendEntries;

    public RaftSimulation(List<NodeId> voters, long seed, Timing timing) {
        this(voters,seed,timing,Map.of(),RaftRpc.MAX_ENTRIES);
    }
    public RaftSimulation(List<NodeId> voters,long seed,Timing timing,Map<NodeId,RaftState> recovered,int maxAppendEntries) {
        this.voters = List.copyOf(voters).stream().sorted(Comparator.comparing(NodeId::value)).toList();
        if (this.voters.size() != 3 || new HashSet<>(this.voters).size() != 3) {
            throw new IllegalArgumentException("Three distinct voters required");
        }
        this.timing = Objects.requireNonNull(timing, "timing");
        if(maxAppendEntries<1 || maxAppendEntries>RaftRpc.MAX_ENTRIES || !this.voters.containsAll(recovered.keySet())) {
            throw new IllegalArgumentException("Invalid bootstrap or append limit");
        }
        this.maxAppendEntries=maxAppendEntries;
        this.seeds = new Random(seed);
        log(null, "seed=" + seed + " timing=" + timing);
        for (NodeId id : this.voters) {
            var node=new Node(id); node.durable=recovered.getOrDefault(id,RaftState.initial()); nodes.put(id,node);
            rememberCommitted(node.durable.hardState().commitIndex(),node.durable.log());
        }
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
                if (failure != DiskFailure.BEFORE_WRITE) save(node, write.request());
                node.pending = null;
                RaftEvent completion = failure == DiskFailure.NONE
                        ? new RaftEvent.PersistCompleted(write.request().id())
                        : new RaftEvent.PersistFailed(write.request().id(), "Injected disk failure: " + failure);
                scheduler.scheduleAfter(timing.completionDelayNanos(), node.id, new Delivery.Input(node.incarnation, completion));
            }
            case Delivery.Apply apply -> {
                if(apply.incarnation()!=node.incarnation) { log(node.id,"drop: old apply"); break; }
                for(LogEntry entry:apply.request().entries()) {
                    if(entry.index()!=node.applied.size()+1L || entry.index()>node.durable.hardState().commitIndex()
                            || !entry.equals(node.durable.log().get(Math.toIntExact(entry.index()-1)))) {
                        throw new AssertionError("Apply before durable commit or out of order at "+node.id);
                    }
                    LogEntry previous=appliedHistory.putIfAbsent(entry.index(),entry);
                    if(previous!=null && !previous.equals(entry))throw new AssertionError("State-machine safety violated");
                    node.applied.add(entry);
                }
                scheduler.scheduleAfter(timing.completionDelayNanos(),node.id,
                        new Delivery.Input(node.incarnation,new RaftEvent.ApplyCompleted(apply.request().id())));
            }
        }
        checkSafety();
        return true;
    }

    public void runSteps(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative event budget");
        for (int i=0; i<count; i++) if (!step()) break;
    }
    public RaftNode.Status status(NodeId id) { return node(id).core.status(); }
    public HardState durableState(NodeId id) { return node(id).durable.hardState(); }
    public List<LogEntry> durableLog(NodeId id) { return node(id).durable.log(); }
    public List<LogEntry> applied(NodeId id) { return List.copyOf(node(id).applied); }
    public Optional<LogPosition> propose(NodeId id,byte[] command) {
        Node node=node(id); if(!node.running)throw new IllegalStateException("Node crashed");
        var effects=deliver(node,new RaftEvent.Propose(command));
        return effects.stream().filter(Effect.ProposalQueued.class::isInstance).map(Effect.ProposalQueued.class::cast)
                .map(p->new LogPosition(p.index(),p.term())).findFirst();
    }
    public boolean running(NodeId id) { return node(id).running; }
    public List<NodeId> leaders() {
        return nodes.values().stream().filter(node -> node.running && node.core.status().role() == RaftNode.Role.LEADER).map(node -> node.id).toList();
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
    public void setLink(NodeId from,NodeId to,boolean enabled) {
        node(from);node(to); var link=new Link(from,to);
        if(enabled)blocked.remove(link); else blocked.add(link);
        log(from,"link to="+to+" enabled="+enabled);
    }

    /** Explicit model choice for an unfinished write. Already durable writes are always retained. */
    public void crash(NodeId id, boolean persistPending) {
        Node node = node(id);
        if (!node.running) throw new IllegalStateException("Node already crashed");
        log(id, "crash persistPending=" + persistPending);
        if (persistPending && node.pending != null) save(node, node.pending);
        node.pending = null; node.running = false; node.nextFailure = DiskFailure.NONE;
    }
    public void restart(NodeId id) {
        Node node = node(id);
        if (node.running && node.core.status().role() != RaftNode.Role.FAILED) throw new IllegalStateException("Node is still running");
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
        var config = new RaftConfig(node.id, voters, node.incarnation, timing.minElectionNanos(), timing.maxElectionNanos(), timing.heartbeatNanos(),maxAppendEntries);
        node.core = new RaftNode(config, node.durable, new SeededRandomSource(seed)); node.applied.clear();
        log(node.id, "boot incarnation=" + node.incarnation + " randomSeed=" + seed + " recovered=" + node.durable);
        deliver(node, new RaftEvent.Start());
    }

    private List<Effect> deliver(Node node, RaftEvent event) {
        var effects=node.core.step(event);
        for (Effect effect : effects) {
            log(node.id, "effect=" + effect);
            switch (effect) {
                case Effect.ScheduleTimer timer -> scheduler.scheduleAfter(timer.delayNanos(), node.id,
                        new Delivery.Input(node.incarnation, new RaftEvent.TimerFired(timer.id(), timer.kind())));
                case Effect.PersistBatch persist -> {
                    if (node.pending != null) throw new AssertionError("Concurrent storage writes at " + node.id);
                    node.pending = persist;
                    scheduler.scheduleAfter(timing.writeDelayNanos(), node.id, new Delivery.Write(node.incarnation, persist));
                }
                case Effect.SendRpc send -> {
                    checkDurableSend(node, send);
                    if (!blocked.contains(new Link(node.id, send.to()))) scheduler.scheduleAfter(timing.rpcDelayNanos(), send.to(), new Delivery.Rpc(node.id, send.rpc()));
                    else log(node.id, "drop outgoing to=" + send.to());
                }
                case Effect.ApplyCommittedEntries apply -> {
                    if(apply.entries().getFirst().index()!=node.applied.size()+1L
                            || apply.entries().getLast().index()>node.durable.hardState().commitIndex()) {
                        throw new AssertionError("Invalid apply effect at "+node.id);
                    }
                    scheduler.scheduleAfter(timing.applyDelayNanos(),node.id,new Delivery.Apply(node.incarnation,apply));
                }
                case Effect.ProposalQueued ignored -> { }
                case Effect.ProposalRejected ignored -> { }
            }
        }
        checkSafety();
        return effects;
    }

    private void checkDurableSend(Node node, Effect.SendRpc send) {
        long term=send.rpc().term();
        if (term != node.durable.hardState().currentTerm()) throw new AssertionError("RPC before durable term: " + send);
        NodeId requiredVote = switch (send.rpc()) {
            case RaftRpc.RequestVote ignored -> node.id;
            case RaftRpc.AppendEntries ignored -> node.id;
            case RaftRpc.AppendResponse ignored -> null;
            case RaftRpc.VoteResponse response -> response.granted() ? send.to() : null;
        };
        if (requiredVote != null && !node.durable.hardState().votedFor().equals(Optional.of(requiredVote))) {
            throw new AssertionError("RPC before durable vote: " + send);
        }
        if(send.rpc() instanceof RaftRpc.AppendEntries request) {
            if(request.leaderCommit()>node.durable.hardState().commitIndex())throw new AssertionError("Advertised unpersisted commit");
            checkPrefix(node,request);
            var previous=requests.putIfAbsent(new RequestKey(node.id,request.requestId()),request);
            if(previous!=null && !previous.equals(request))throw new AssertionError("Reused RPC id with new contents");
        }
        if(send.rpc() instanceof RaftRpc.AppendResponse response && response.success()) {
            var request=requests.get(new RequestKey(send.to(),response.requestId()));
            if(request==null || response.matchIndex()!=request.lastIndex())throw new AssertionError("Uncorrelated append success");
            checkPrefix(node,request);
        }
    }

    private void checkPrefix(Node node,RaftRpc.AppendEntries request) {
        if(request.lastIndex()>node.durable.log().size() || (request.prevLogIndex()>0
                && node.durable.log().get(Math.toIntExact(request.prevLogIndex()-1)).term()!=request.prevLogTerm())) {
            throw new AssertionError("Append before durable prefix at "+node.id);
        }
        for(LogEntry entry:request.entries())if(!entry.equals(node.durable.log().get(Math.toIntExact(entry.index()-1)))) {
            throw new AssertionError("Append before durable entry at "+node.id);
        }
    }
    private void save(Node node, Effect.PersistBatch batch) {
        HardState state=batch.state(), before=node.durable.hardState();
        if (state.currentTerm() < before.currentTerm() || state.commitIndex() < before.commitIndex()
                || (state.currentTerm() == before.currentTerm() && before.votedFor().isPresent()
                    && !state.votedFor().equals(before.votedFor()))) {
            throw new AssertionError("Invalid persisted metadata transition: " + node.durable + " -> " + state);
        }
        var entries=new ArrayList<>(node.durable.log());
        if(batch.truncateFromIndex()>0) {
            if(batch.truncateFromIndex()<=before.commitIndex() || batch.truncateFromIndex()>entries.size()+1L) {
                throw new AssertionError("Invalid durable truncate");
            }
            entries.subList(Math.toIntExact(batch.truncateFromIndex()-1),entries.size()).clear();
        }
        entries.addAll(batch.entries()); node.durable=new RaftState(state,entries); log(node.id,"durable="+node.durable);
    }

    private void checkSafety() {
        for (Node node : nodes.values()) {
            if(node.core==null)continue;
            var status = node.core.status();
            rememberCommitted(status.hardState().commitIndex(),node.core.log());
            if(status.lastApplied()>node.applied.size() || node.applied.size()>node.durable.hardState().commitIndex())throw new AssertionError("Invalid apply bounds");
            if (!node.running || status.role() != RaftNode.Role.LEADER) continue;
            if (status.votes().size() < 2 || status.hardState().currentTerm()!=node.durable.hardState().currentTerm()
                    || !node.durable.hardState().votedFor().equals(Optional.of(node.id))) throw new AssertionError("Invalid leader at " + node.id);
            NodeId previous = elected.putIfAbsent(status.hardState().currentTerm(), node.id);
            if (previous != null && !previous.equals(node.id)) {
                throw new AssertionError("Two leaders in term " + status.hardState().currentTerm() + ": " + previous + ", " + node.id);
            }
            if(previous==null)for(var entry:committed.entrySet()) {
                if(entry.getKey()>node.core.log().size() || !entry.getValue().equals(node.core.log().get(Math.toIntExact(entry.getKey()-1)))) {
                    throw new AssertionError("Leader completeness violated at "+node.id);
                }
            }
        }
        var all=nodes.values().stream().filter(n->n.core!=null).toList();
        for(int a=0;a<all.size();a++)for(int b=a+1;b<all.size();b++) {
            var left=all.get(a).core.log(); var right=all.get(b).core.log(); boolean equalPrefix=true;
            for(int i=0;i<Math.min(left.size(),right.size());i++) {
                equalPrefix &= left.get(i).equals(right.get(i));
                if(left.get(i).term()==right.get(i).term() && !equalPrefix)throw new AssertionError("Log matching violated at index "+(i+1));
            }
        }
    }
    private void rememberCommitted(long commit,List<LogEntry> entries) {
        for(int i=0;i<commit;i++) {
            var entry=entries.get(i); var previous=committed.putIfAbsent(entry.index(),entry);
            if(previous!=null && !previous.equals(entry))throw new AssertionError("Committed prefix changed at "+entry.index());
        }
    }

    private Node node(NodeId id) {
        Node node = nodes.get(Objects.requireNonNull(id, "id"));
        if (node == null) throw new IllegalArgumentException("Unknown node: " + id);
        return node;
    }
    private void log(NodeId id, String action) { trace.add(scheduler.nowNanos() + " " + (id == null ? "*" : id.value()) + " " + action); }
}
