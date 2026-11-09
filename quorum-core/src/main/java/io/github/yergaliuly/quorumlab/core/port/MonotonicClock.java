package io.github.yergaliuly.quorumlab.core.port;

/** Elapsed nanoseconds from an arbitrary origin; nonnegative and never moves backwards. */
@FunctionalInterface
public interface MonotonicClock {
    long nowNanos();
}
