package io.github.yergaliuly.quorumlab.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReplicationContractsTest {
    private static final OperationId ID = new OperationId(1,1);
    static LogEntry entry(long index, long term) { return new LogEntry(index,term,LogEntry.Type.NO_OP,new byte[0]); }

    @Test void commandIsImmutableAndUsesContentEquality() {
        byte[] data={1,2}; var entry=new LogEntry(1,2,LogEntry.Type.COMMAND,data);
        data[0]=9; entry.command()[1]=9;
        assertArrayEquals(new byte[]{1,2},entry.command());
        assertEquals(new LogEntry(1,2,LogEntry.Type.COMMAND,new byte[]{1,2}),entry);
        assertEquals(new LogEntry(1,2,LogEntry.Type.COMMAND,new byte[]{1,2}).hashCode(),entry.hashCode());
        assertThrows(IllegalArgumentException.class,()->new LogEntry(1,1,LogEntry.Type.NO_OP,new byte[]{1}));
        assertThrows(IllegalArgumentException.class,()->new LogEntry(0,1,LogEntry.Type.COMMAND,data));
        assertThrows(IllegalArgumentException.class,()->new LogEntry(1,0,LogEntry.Type.COMMAND,data));
        assertThrows(IllegalArgumentException.class,()->new LogEntry(1,1,LogEntry.Type.COMMAND,new byte[LogEntry.MAX_COMMAND_BYTES+1]));
    }
    @Test void recoveredLogMustBeContiguousMonotoneAndCoverCommit() {
        var state=new HardState(3,Optional.empty(),1);
        var entries=new ArrayList<>(List.of(entry(1,1),entry(2,3)));
        var recovered=new RaftState(state,entries); entries.clear();
        assertEquals(new LogPosition(2,3),recovered.lastLogPosition());
        assertThrows(UnsupportedOperationException.class,()->recovered.log().clear());
        assertThrows(IllegalArgumentException.class,()->new RaftState(state,List.of()));
        assertThrows(IllegalArgumentException.class,()->new RaftState(state,List.of(entry(2,1))));
        assertThrows(IllegalArgumentException.class,()->new RaftState(state,List.of(entry(1,3),entry(2,2))));
        assertThrows(IllegalArgumentException.class,()->new RaftState(state,List.of(entry(1,4))));
    }
    @Test void appendValidatesPositionTermsAndBoundsAndCopiesList() {
        var entries=new ArrayList<>(List.of(entry(2,3)));
        var rpc=new RaftRpc.AppendEntries(3,ID,1,2,entries,0); entries.clear();
        assertEquals(1,rpc.entries().size());
        assertThrows(UnsupportedOperationException.class,()->rpc.entries().clear());
        assertThrows(IllegalArgumentException.class,()->new RaftRpc.AppendEntries(3,ID,0,1,List.of(),0));
        assertThrows(IllegalArgumentException.class,()->new RaftRpc.AppendEntries(3,ID,1,2,List.of(entry(3,3)),0));
        assertThrows(IllegalArgumentException.class,()->new RaftRpc.AppendEntries(3,ID,1,2,List.of(entry(2,1)),0));
        assertThrows(IllegalArgumentException.class,()->new RaftRpc.AppendEntries(3,ID,0,0,List.of(entry(1,4)),0));
        assertThrows(IllegalArgumentException.class,()->new RaftRpc.AppendEntries(3,ID,0,0,List.of(),-1));
        var many=new ArrayList<LogEntry>(); for(int i=1;i<=65;i++)many.add(entry(i,1));
        assertThrows(IllegalArgumentException.class,()->new RaftRpc.AppendEntries(3,ID,0,0,many,0));
        var big=new LogEntry(1,1,LogEntry.Type.COMMAND,new byte[LogEntry.MAX_COMMAND_BYTES]);
        assertDoesNotThrow(()->new RaftRpc.AppendEntries(1,ID,0,0,List.of(big),0));
        assertThrows(IllegalArgumentException.class,()->new RaftRpc.AppendEntries(1,ID,0,0,List.of(big,entry(2,1)),0));
        assertThrows(IllegalArgumentException.class,()->new RaftRpc.AppendResponse(1,ID,false,2));
        assertThrows(IllegalArgumentException.class,()->new RaftRpc.AppendResponse(0,ID,true,0));
    }
    @Test void batchesAndProposalOwnTheirInputs() {
        var entries=new ArrayList<>(List.of(entry(2,1)));
        var batch=new Effect.PersistBatch(ID,new HardState(1,Optional.empty(),0),2,entries);
        entries.clear(); assertEquals(1,batch.entries().size());
        assertThrows(IllegalArgumentException.class,()->new Effect.PersistBatch(ID,batch.state(),3,batch.entries()));
        assertThrows(IllegalArgumentException.class,()->new Effect.ApplyCommittedEntries(ID,List.of()));
        assertThrows(IllegalArgumentException.class,()->new Effect.ApplyCommittedEntries(ID,List.of(entry(1,1),entry(3,1))));
        byte[] bytes={7}; var proposal=new RaftEvent.Propose(bytes); bytes[0]=8; proposal.command()[0]=9;
        assertArrayEquals(new byte[]{7},proposal.command());
    }
}
