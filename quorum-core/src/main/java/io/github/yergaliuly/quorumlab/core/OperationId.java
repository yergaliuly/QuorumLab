package io.github.yergaliuly.quorumlab.core;

/** Correlation within one node: a new incarnation on restart, a fresh sequence per operation. */
public record OperationId(long incarnation, long sequence) {
    public OperationId {
        if (incarnation <= 0 || sequence <= 0) {
            throw new IllegalArgumentException("Incarnation and sequence must be positive");
        }
    }
}
