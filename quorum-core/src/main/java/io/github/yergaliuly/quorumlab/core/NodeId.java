package io.github.yergaliuly.quorumlab.core;

import java.util.Objects;

/** Logical identity of a node, independent of its network address. */
public record NodeId(String value) {
    public NodeId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Node id must not be blank");
        }
    }
}
