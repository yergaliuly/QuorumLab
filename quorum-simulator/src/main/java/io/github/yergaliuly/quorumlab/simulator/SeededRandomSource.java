package io.github.yergaliuly.quorumlab.simulator;

import io.github.yergaliuly.quorumlab.core.port.RandomSource;
import java.util.Random;

/** Explicit, per-node pseudorandom stream. Reproduction also requires the same build/config/event script. */
public final class SeededRandomSource implements RandomSource {
    private final Random random;
    public SeededRandomSource(long seed) { random = new Random(seed); }
    @Override public long nextLong(long originInclusive, long boundExclusive) { return random.nextLong(originInclusive, boundExclusive); }
}
