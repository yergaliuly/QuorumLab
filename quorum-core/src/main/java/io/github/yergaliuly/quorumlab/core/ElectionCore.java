package io.github.yergaliuly.quorumlab.core;

import io.github.yergaliuly.quorumlab.core.port.RandomSource;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Election-only state machine. One serialized driver supplies events, seeded randomness and recovered metadata. */
public final class ElectionCore implements RaftCore {
    public enum Role { FOLLOWER, CANDIDATE, LEADER, FAILED }
    public record Status(Role role, HardState hardState, HardState durableState, Optional<NodeId> leader,
                         Optional<OperationId> pendingPersist, Set<NodeId> votes, Optional<String> failure) {
        public Status { votes = Set.copyOf(votes); }
    }
    private record Reply(long term, NodeId to, boolean granted) { }
    private final ElectionConfig config;
    private final LogPosition lastLog;
    private final RandomSource random;
    private final Set<NodeId> votes = new HashSet<>();
    private HardState current;
    private HardState durable;
    private Role role = Role.FOLLOWER;
    private NodeId leader;
    private Effect.PersistHardState pending;
    private Reply reply;
    private OperationId electionTimer;
    private OperationId heartbeatTimer;
    private long sequence;
    private boolean started;
    private boolean campaignSent;
    private String failure;

    public ElectionCore(ElectionConfig config, HardState recovered, LogPosition lastLog, RandomSource random) {
        this.config = Objects.requireNonNull(config, "config");
        this.current = this.durable = Objects.requireNonNull(recovered, "recovered");
        this.lastLog = Objects.requireNonNull(lastLog, "lastLog");
        this.random = Objects.requireNonNull(random, "random");
        if (recovered.commitIndex() > lastLog.index() || lastLog.term() > recovered.currentTerm()
                || recovered.votedFor().filter(node -> !config.voters().contains(node)).isPresent()) {
            throw new IllegalArgumentException("Recovered metadata conflicts with membership or last log position");
        }
    }

    public Status status() {
        return new Status(role, current, durable, Optional.ofNullable(leader),
                Optional.ofNullable(pending).map(Effect.PersistHardState::id), votes, Optional.ofNullable(failure));
    }

    @Override public List<Effect> step(RaftEvent event) {
        Objects.requireNonNull(event, "event");
        if (role == Role.FAILED) return List.of();
        var effects = new ArrayList<Effect>();
        try {
            if (event instanceof RaftEvent.Start) {
                if (started) return List.of();
                started = true;
                armElection(effects);
            } else {
                if (!started) return List.of();
                switch (event) {
                    case RaftEvent.TimerFired timer -> timeout(timer, effects);
                    case RaftEvent.RpcReceived rpc -> receive(rpc, effects);
                    case RaftEvent.PersistCompleted done -> {
                        if (pending == null || !pending.id().equals(done.id())) return List.of();
                        durable = pending.state(); pending = null;
                    }
                    case RaftEvent.PersistFailed error -> {
                        if (pending == null || !pending.id().equals(error.id())) return List.of();
                        stop("Persistence outcome unknown: " + error.reason());
                    }
                    case RaftEvent.Start ignored -> throw new IllegalStateException("Start already handled");
                }
            }
            flush(effects);
            return List.copyOf(effects);
        } catch (RuntimeException invalidRuntime) {
            // Overflow/broken injected RNG cannot leave a partially released list of effects.
            stop("Election stopped: " + invalidRuntime);
            return List.of();
        }
    }

    private void timeout(RaftEvent.TimerFired timer, List<Effect> effects) {
        if (timer.kind() == TimerKind.HEARTBEAT) {
            if (role == Role.LEADER && timer.id().equals(heartbeatTimer)) sendHeartbeats(effects);
            return;
        }
        if (role == Role.LEADER || !timer.id().equals(electionTimer)) return;
        electionTimer = null;
        if (pending != null) { armElection(effects); return; }
        long nextTerm = Math.incrementExact(current.currentTerm());
        current = new HardState(nextTerm, Optional.of(config.self()), current.commitIndex());
        role = Role.CANDIDATE; leader = null; votes.clear(); reply = null; campaignSent = false;
        armElection(effects);
    }

    private void receive(RaftEvent.RpcReceived envelope, List<Effect> effects) {
        NodeId from = envelope.from();
        if (from.equals(config.self()) || !config.voters().contains(from)) return;
        long term = switch (envelope.rpc()) {
            case RaftRpc.RequestVote request -> request.term();
            case RaftRpc.VoteResponse response -> response.term();
            case RaftRpc.Heartbeat heartbeat -> heartbeat.term();
        };
        boolean higher = term > current.currentTerm();
        if (higher) {
            current = new HardState(term, Optional.empty(), current.commitIndex());
            follow();
            // A rejected candidate must not repeatedly postpone a running follower election timer.
            if (electionTimer == null) armElection(effects);
        }
        switch (envelope.rpc()) {
            case RaftRpc.RequestVote request -> requestVote(from, request, higher, effects);
            case RaftRpc.VoteResponse response -> {
                if (role == Role.CANDIDATE && campaignSent && ready()
                        && response.term() == current.currentTerm() && response.granted()) {
                    votes.add(from);
                    if (votes.size() >= 2) {
                        role = Role.LEADER; leader = config.self(); electionTimer = null;
                        sendHeartbeats(effects);
                    }
                }
            }
            case RaftRpc.Heartbeat heartbeat -> {
                if (heartbeat.term() == current.currentTerm()) {
                    follow(); leader = from; armElection(effects);
                }
            }
        }
    }

    private void requestVote(NodeId from, RaftRpc.RequestVote request, boolean higher, List<Effect> effects) {
        if (request.term() < current.currentTerm()) {
            if (ready()) effects.add(new Effect.SendRpc(from, new RaftRpc.VoteResponse(current.currentTerm(), false)));
            return;
        }
        boolean fresh = request.lastLogTerm() > lastLog.term()
                || (request.lastLogTerm() == lastLog.term() && request.lastLogIndex() >= lastLog.index());
        boolean granted = fresh && (current.votedFor().isEmpty() || current.votedFor().get().equals(from));
        if (granted) {
            current = new HardState(current.currentTerm(), Optional.of(from), current.commitIndex());
            reply = new Reply(current.currentTerm(), from, true);
            armElection(effects);
        } else if (ready()) {
            effects.add(new Effect.SendRpc(from, new RaftRpc.VoteResponse(current.currentTerm(), false)));
        } else if (higher) {
            // Keep the higher-term denial until its term is durable; don't overwrite a pending grant later.
            reply = new Reply(current.currentTerm(), from, false);
        }
    }

    private boolean ready() { return pending == null && current.equals(durable); }

    private void flush(List<Effect> effects) {
        if (role == Role.FAILED || pending != null) return;
        if (!current.equals(durable)) {
            pending = new Effect.PersistHardState(nextId(), current);
            effects.add(pending); return;
        }
        if (reply != null) {
            if (reply.term() == current.currentTerm()
                    && (!reply.granted() || current.votedFor().equals(Optional.of(reply.to())))) {
                effects.add(new Effect.SendRpc(reply.to(), new RaftRpc.VoteResponse(reply.term(), reply.granted())));
            }
            reply = null;
        }
        if (role == Role.CANDIDATE && !campaignSent) {
            campaignSent = true; votes.add(config.self());
            for (NodeId peer : config.voters()) {
                if (!peer.equals(config.self())) effects.add(new Effect.SendRpc(peer,
                        new RaftRpc.RequestVote(current.currentTerm(), lastLog.index(), lastLog.term())));
            }
        }
    }

    private void follow() {
        role = Role.FOLLOWER; leader = null; votes.clear(); reply = null;
        campaignSent = false; heartbeatTimer = null;
    }

    private void armElection(List<Effect> effects) {
        long delay = random.nextLong(config.minElectionNanos(), config.maxElectionNanos());
        if (delay < config.minElectionNanos() || delay >= config.maxElectionNanos()) {
            throw new IllegalStateException("RandomSource returned a timeout outside its requested bounds");
        }
        electionTimer = nextId();
        effects.add(new Effect.ScheduleTimer(electionTimer, TimerKind.ELECTION, delay));
    }

    private void sendHeartbeats(List<Effect> effects) {
        for (NodeId peer : config.voters()) {
            if (!peer.equals(config.self())) effects.add(new Effect.SendRpc(peer, new RaftRpc.Heartbeat(current.currentTerm())));
        }
        heartbeatTimer = nextId();
        effects.add(new Effect.ScheduleTimer(heartbeatTimer, TimerKind.HEARTBEAT, config.heartbeatNanos()));
    }

    private OperationId nextId() { sequence = Math.incrementExact(sequence); return new OperationId(config.incarnation(), sequence); }
    private void stop(String reason) {
        role = Role.FAILED; failure = reason; leader = null; reply = null;
        votes.clear(); electionTimer = null; heartbeatTimer = null;
    }
}
