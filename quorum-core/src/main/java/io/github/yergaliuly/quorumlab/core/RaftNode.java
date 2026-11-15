package io.github.yergaliuly.quorumlab.core;

import io.github.yergaliuly.quorumlab.core.port.RandomSource;
import java.util.*;

/** Single-threaded, deterministic Raft state machine. All I/O and application work are effects. */
public final class RaftNode implements RaftCore {
    public enum Role { FOLLOWER, CANDIDATE, LEADER, FAILED }
    public record Progress(long nextIndex,long matchIndex,Optional<OperationId> requestId) { }
    public record Status(Role role, HardState hardState, HardState durableState, Optional<NodeId> leader,
                         Optional<OperationId> pendingPersist, Set<NodeId> votes, Optional<String> failure,
                         long lastApplied, Optional<OperationId> pendingApply, Map<NodeId,Progress> progress) {
        public Status { votes=Set.copyOf(votes); progress=Map.copyOf(progress); }
    }
    private record VoteReply(long term,NodeId to,boolean granted) { }
    private record AppendReply(long term,NodeId to,OperationId id,long matched) { }
    private static final class Peer {
        long next, matched;
        RaftRpc.AppendEntries request;
        boolean wanted=true;
        Peer(long next) { this.next=next; }
    }
    private final RaftConfig config;
    private final RandomSource random;
    private final ArrayList<LogEntry> log;
    private final Set<NodeId> votes=new HashSet<>();
    private final Map<NodeId,Peer> peers=new LinkedHashMap<>();
    private HardState current;
    private RaftState durable;
    private Role role=Role.FOLLOWER;
    private NodeId leader;
    private Effect.PersistBatch pending;
    private RaftState pendingResult;
    private Effect.ApplyCommittedEntries applying;
    private VoteReply voteReply;
    private AppendReply appendReply;
    private OperationId electionTimer,heartbeatTimer;
    private long sequence,lastApplied;
    private boolean started,campaignSent;
    private String failure;

    public RaftNode(RaftConfig config,RaftState recovered,RandomSource random) {
        this.config=Objects.requireNonNull(config,"config");
        this.durable=Objects.requireNonNull(recovered,"recovered");
        this.current=recovered.hardState(); this.log=new ArrayList<>(recovered.log());
        this.random=Objects.requireNonNull(random,"random");
        if(current.votedFor().filter(node->!config.voters().contains(node)).isPresent()) {
            throw new IllegalArgumentException("Recovered vote outside membership");
        }
    }
    public List<LogEntry> log() { return List.copyOf(log); }
    public RaftState durableState() { return durable; }
    public Status status() {
        var progress=new LinkedHashMap<NodeId,Progress>();
        peers.forEach((id,p)->progress.put(id,new Progress(p.next,p.matched,Optional.ofNullable(p.request).map(RaftRpc.AppendEntries::requestId))));
        return new Status(role,current,durable.hardState(),Optional.ofNullable(leader),
                Optional.ofNullable(pending).map(Effect.PersistBatch::id),votes,Optional.ofNullable(failure),
                lastApplied,Optional.ofNullable(applying).map(Effect.ApplyCommittedEntries::id),progress);
    }

    @Override public List<Effect> step(RaftEvent event) {
        Objects.requireNonNull(event,"event");
        if(role==Role.FAILED) return event instanceof RaftEvent.Propose
                ?List.of(new Effect.ProposalRejected(Effect.ProposalRejected.Reason.FAILED,Optional.empty())):List.of();
        var effects=new ArrayList<Effect>();
        try {
            if(event instanceof RaftEvent.Start) {
                if(started)return List.of(); started=true; armElection(effects);
            } else {
                if(!started)return List.of();
                switch(event) {
                    case RaftEvent.TimerFired timer -> timeout(timer,effects);
                    case RaftEvent.RpcReceived rpc -> receive(rpc,effects);
                    case RaftEvent.Propose proposal -> propose(proposal,effects);
                    case RaftEvent.PersistCompleted done -> {
                        if(pending==null || !pending.id().equals(done.id()))return List.of();
                        durable=pendingResult; pending=null; pendingResult=null;
                        if(role==Role.LEADER) peers.values().forEach(p->p.wanted=true);
                    }
                    case RaftEvent.PersistFailed error -> {
                        if(pending==null || !pending.id().equals(error.id()))return List.of();
                        stop("Persistence outcome unknown: "+error.reason());
                    }
                    case RaftEvent.ApplyCompleted done -> {
                        if(applying==null || !applying.id().equals(done.id()))return List.of();
                        lastApplied=applying.entries().getLast().index(); applying=null;
                    }
                    case RaftEvent.ApplyFailed error -> {
                        if(applying==null || !applying.id().equals(error.id()))return List.of();
                        stop("Application outcome unknown: "+error.reason());
                    }
                    case RaftEvent.Start ignored -> throw new IllegalStateException("Start already handled");
                }
            }
            flush(effects); return List.copyOf(effects);
        } catch(RuntimeException invalidRuntime) {
            // Do not release a partial effect list after overflow, broken RNG or invariant failure.
            stop("Raft stopped: "+invalidRuntime); return List.of();
        }
    }

    private void timeout(RaftEvent.TimerFired timer,List<Effect> effects) {
        if(timer.kind()==TimerKind.HEARTBEAT) {
            if(role==Role.LEADER && timer.id().equals(heartbeatTimer)) {
                peers.values().forEach(p->p.wanted=true); armHeartbeat(effects);
            }
            return;
        }
        if(role==Role.LEADER || !timer.id().equals(electionTimer))return;
        electionTimer=null;
        if(!ready()) { armElection(effects); return; }
        current=new HardState(Math.incrementExact(current.currentTerm()),Optional.of(config.self()),current.commitIndex());
        follow(); role=Role.CANDIDATE; armElection(effects);
    }

    private void receive(RaftEvent.RpcReceived envelope,List<Effect> effects) {
        NodeId from=envelope.from();
        if(from.equals(config.self()) || !config.voters().contains(from))return;
        boolean higher=envelope.rpc().term()>current.currentTerm();
        if(higher) {
            current=new HardState(envelope.rpc().term(),Optional.empty(),current.commitIndex()); follow();
            if(electionTimer==null)armElection(effects);
        }
        switch(envelope.rpc()) {
            case RaftRpc.RequestVote request -> requestVote(from,request,higher,effects);
            case RaftRpc.VoteResponse response -> {
                if(role==Role.CANDIDATE && campaignSent && ready() && response.term()==current.currentTerm() && response.granted()) {
                    votes.add(from); if(votes.size()>=2)becomeLeader(effects);
                }
            }
            case RaftRpc.AppendEntries request -> append(from,request,effects);
            case RaftRpc.AppendResponse response -> appended(from,response);
        }
    }

    private void requestVote(NodeId from,RaftRpc.RequestVote request,boolean higher,List<Effect> effects) {
        if(request.term()<current.currentTerm()) {
            if(ready())effects.add(new Effect.SendRpc(from,new RaftRpc.VoteResponse(current.currentTerm(),false)));
            return;
        }
        long lastTerm=termAt(log.size());
        boolean fresh=request.lastLogTerm()>lastTerm || (request.lastLogTerm()==lastTerm && request.lastLogIndex()>=log.size());
        boolean granted=fresh && (current.votedFor().isEmpty() || current.votedFor().get().equals(from));
        if(granted) {
            current=new HardState(current.currentTerm(),Optional.of(from),current.commitIndex());
            voteReply=new VoteReply(current.currentTerm(),from,true); armElection(effects);
        } else if(ready()) effects.add(new Effect.SendRpc(from,new RaftRpc.VoteResponse(current.currentTerm(),false)));
        else if(higher)voteReply=new VoteReply(current.currentTerm(),from,false);
    }

    private void becomeLeader(List<Effect> effects) {
        role=Role.LEADER; leader=config.self(); electionTimer=null;
        for(NodeId peer:config.voters())if(!peer.equals(config.self()))peers.put(peer,new Peer(log.size()+1L));
        var noop=new LogEntry(log.size()+1L,current.currentTerm(),LogEntry.Type.NO_OP,new byte[0]);
        log.add(noop); persist(0,List.of(noop),effects); armHeartbeat(effects);
    }

    private void append(NodeId from,RaftRpc.AppendEntries request,List<Effect> effects) {
        if(request.term()<current.currentTerm()) { if(ready())reject(from,request,effects); return; }
        // Preserve a pending reply to retransmission by the same leader; a new leader invalidates it.
        if(role!=Role.FOLLOWER || !from.equals(leader))follow();
        leader=from; armElection(effects);
        if(!ready())return; // Sender retries. Never queue log mutations behind an unfinished write.
        if(request.prevLogIndex()>log.size() || termAt(request.prevLogIndex())!=request.prevLogTerm()) {
            reject(from,request,effects); return;
        }
        long truncate=0;
        List<LogEntry> additions=List.of();
        for(int i=0;i<request.entries().size();i++) {
            LogEntry incoming=request.entries().get(i);
            if(incoming.index()<=log.size()) {
                LogEntry local=log.get(Math.toIntExact(incoming.index()-1));
                if(local.term()==incoming.term()) {
                    if(!local.equals(incoming))throw new IllegalStateException("Same index/term with different command");
                    continue;
                }
                if(incoming.index()<=current.commitIndex())throw new IllegalStateException("Attempt to replace committed entry");
                truncate=incoming.index(); log.subList(Math.toIntExact(truncate-1),log.size()).clear();
            }
            additions=request.entries().subList(i,request.entries().size()); log.addAll(additions); break;
        }
        long commit=Math.max(current.commitIndex(),Math.min(request.leaderCommit(),request.lastIndex()));
        boolean changed=truncate!=0 || !additions.isEmpty() || commit!=current.commitIndex();
        current=new HardState(current.currentTerm(),current.votedFor(),commit);
        appendReply=new AppendReply(current.currentTerm(),from,request.requestId(),request.lastIndex());
        if(changed)persist(truncate,additions,effects);
    }
    private void reject(NodeId from,RaftRpc.AppendEntries request,List<Effect> effects) {
        effects.add(new Effect.SendRpc(from,new RaftRpc.AppendResponse(current.currentTerm(),request.requestId(),false,0)));
    }

    private void appended(NodeId from,RaftRpc.AppendResponse response) {
        if(role!=Role.LEADER || response.term()!=current.currentTerm())return;
        Peer peer=peers.get(from);
        if(peer.request==null || !peer.request.requestId().equals(response.requestId()))return;
        if(response.success()) {
            if(response.matchIndex()!=peer.request.lastIndex())return;
            long sentCommit=peer.request.leaderCommit();
            peer.matched=Math.max(peer.matched,response.matchIndex()); peer.next=peer.matched+1;
            peer.wanted=peer.next<=durable.log().size() || sentCommit<durable.hardState().commitIndex();
        } else {
            peer.next=Math.max(peer.matched+1,Math.max(1,peer.next-1)); peer.wanted=true;
        }
        peer.request=null;
    }

    private void propose(RaftEvent.Propose proposal,List<Effect> effects) {
        if(role!=Role.LEADER || !ready()) {
            effects.add(new Effect.ProposalRejected(role==Role.LEADER?Effect.ProposalRejected.Reason.BUSY:Effect.ProposalRejected.Reason.NOT_LEADER,Optional.ofNullable(leader)));
            return;
        }
        var entry=new LogEntry(log.size()+1L,current.currentTerm(),LogEntry.Type.COMMAND,proposal.command());
        log.add(entry); effects.add(new Effect.ProposalQueued(entry.index(),entry.term())); persist(0,List.of(entry),effects);
    }

    private boolean ready() { return pending==null && current.equals(durable.hardState()); }
    private void persist(long truncate,List<LogEntry> entries,List<Effect> effects) {
        if(pending!=null)throw new IllegalStateException("Concurrent core persist");
        pendingResult=new RaftState(current,log);
        pending=new Effect.PersistBatch(nextId(),current,truncate,entries); effects.add(pending);
    }
    private void flush(List<Effect> effects) {
        if(role==Role.FAILED || pending!=null)return;
        if(!current.equals(durable.hardState())) { persist(0,List.of(),effects); return; }
        if(voteReply!=null) {
            if(voteReply.term()==current.currentTerm() && (!voteReply.granted() || current.votedFor().equals(Optional.of(voteReply.to())))) {
                effects.add(new Effect.SendRpc(voteReply.to(),new RaftRpc.VoteResponse(voteReply.term(),voteReply.granted())));
            }
            voteReply=null;
        }
        if(appendReply!=null) {
            if(role==Role.FOLLOWER && appendReply.term()==current.currentTerm() && appendReply.to().equals(leader)) {
                effects.add(new Effect.SendRpc(appendReply.to(),new RaftRpc.AppendResponse(appendReply.term(),appendReply.id(),true,appendReply.matched())));
            }
            appendReply=null;
        }
        if(role==Role.CANDIDATE && !campaignSent) {
            campaignSent=true; votes.add(config.self());
            for(NodeId peer:config.voters())if(!peer.equals(config.self()))effects.add(new Effect.SendRpc(peer,
                    new RaftRpc.RequestVote(current.currentTerm(),log.size(),termAt(log.size()))));
        }
        if(role==Role.LEADER) {
            for(long index=durable.log().size();index>current.commitIndex();index--) {
                if(termAt(index)!=current.currentTerm())continue;
                int replicas=1; for(Peer peer:peers.values())if(peer.matched>=index)replicas++;
                if(replicas>=2) {
                    current=new HardState(current.currentTerm(),current.votedFor(),index);
                    persist(0,List.of(),effects); return;
                }
            }
            for(var entry:peers.entrySet())sendAppend(entry.getKey(),entry.getValue(),effects);
        }
        if(applying==null && lastApplied<durable.hardState().commitIndex()) {
            int end=Math.toIntExact(Math.min(durable.hardState().commitIndex(),lastApplied+RaftRpc.MAX_ENTRIES));
            applying=new Effect.ApplyCommittedEntries(nextId(),durable.log().subList(Math.toIntExact(lastApplied),end));
            effects.add(applying);
        }
    }
    private void sendAppend(NodeId to,Peer peer,List<Effect> effects) {
        if(!peer.wanted)return;
        if(peer.request==null) {
            long prev=peer.next-1;
            var entries=new ArrayList<LogEntry>(); int bytes=0;
            for(int i=Math.toIntExact(prev);i<durable.log().size() && entries.size()<config.maxAppendEntries();i++) {
                LogEntry next=durable.log().get(i);
                if(bytes+next.payloadBytes()>RaftRpc.MAX_ENTRY_PAYLOAD_BYTES)break;
                entries.add(next); bytes+=next.payloadBytes();
            }
            peer.request=new RaftRpc.AppendEntries(current.currentTerm(),nextId(),prev,termAt(prev),entries,durable.hardState().commitIndex());
        }
        effects.add(new Effect.SendRpc(to,peer.request)); peer.wanted=false;
    }
    private long termAt(long index) { return index==0?0:log.get(Math.toIntExact(index-1)).term(); }
    private void follow() {
        role=Role.FOLLOWER; leader=null; votes.clear(); voteReply=null; appendReply=null;
        campaignSent=false; heartbeatTimer=null; peers.clear();
    }
    private void armElection(List<Effect> effects) {
        long delay=random.nextLong(config.minElectionNanos(),config.maxElectionNanos());
        if(delay<config.minElectionNanos() || delay>=config.maxElectionNanos())throw new IllegalStateException("RandomSource returned timeout outside bounds");
        electionTimer=nextId(); effects.add(new Effect.ScheduleTimer(electionTimer,TimerKind.ELECTION,delay));
    }
    private void armHeartbeat(List<Effect> effects) {
        heartbeatTimer=nextId(); effects.add(new Effect.ScheduleTimer(heartbeatTimer,TimerKind.HEARTBEAT,config.heartbeatNanos()));
    }
    private OperationId nextId() { sequence=Math.incrementExact(sequence); return new OperationId(config.incarnation(),sequence); }
    private void stop(String reason) { follow(); role=Role.FAILED; failure=reason; electionTimer=null; }
}
