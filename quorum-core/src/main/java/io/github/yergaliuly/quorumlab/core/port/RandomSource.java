package io.github.yergaliuly.quorumlab.core.port;

/**
 * Injected source for timeout selection, never an implicit global RNG.
 * A simulator must use an explicit seed and reproduce the same draw sequence.
 */
@FunctionalInterface
public interface RandomSource {
    /** Returns originInclusive <= result < boundExclusive; requires originInclusive < boundExclusive. */
    long nextLong(long originInclusive, long boundExclusive);
}
