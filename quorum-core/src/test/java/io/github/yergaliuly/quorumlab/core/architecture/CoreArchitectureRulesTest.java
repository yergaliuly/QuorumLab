package io.github.yergaliuly.quorumlab.core.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.yergaliuly.quorumlab.core.port.MonotonicClock;
import io.github.yergaliuly.quorumlab.core.port.RandomSource;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** These fixtures are inspected as bytecode, never executed. */
class CoreArchitectureRulesTest {
    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("forbiddenFixtures")
    void detectsForbiddenAccess(Class<?> fixture) {
        var classes = new ClassFileImporter().importClasses(fixture);
        assertTrue(CoreArchitectureRules.all().stream().anyMatch(rule -> rule.evaluate(classes).hasViolation()),
                () -> "No architecture rule detected " + fixture.getSimpleName());
    }

    static Stream<Class<?>> forbiddenFixtures() {
        return Stream.of(ForeignDependency.class, FileAccess.class, NetworkAccess.class,
                WallClockCall.class, WallClockReference.class, CalendarTime.class,
                ThreadAccess.class, RandomAccess.class, RandomCall.class,
                ConsoleAccess.class, ParallelStream.class);
    }

    @Test
    void allowsPureJdkOperationsAndInjectedSources() {
        var classes = new ClassFileImporter().importClasses(PureCode.class);
        CoreArchitectureRules.all().forEach(rule -> rule.check(classes));
    }

    static class ForeignDependency {
        org.junit.jupiter.api.Test annotation;
    }

    static class FileAccess {
        String read(Path path) throws IOException { return Files.readString(path); }
    }

    static class NetworkAccess {
        Socket socket;
    }

    static class WallClockCall {
        long time() { return System.currentTimeMillis(); }
    }

    static class WallClockReference {
        LongSupplier time() { return System::nanoTime; }
    }

    static class CalendarTime {
        Instant time() { return Instant.now(); }
    }

    static class ThreadAccess {
        void waitForTime() throws InterruptedException { Thread.sleep(1); }
    }

    static class RandomAccess {
        Random random = new Random(42);
    }

    static class RandomCall {
        double number() { return Math.random(); }
    }

    static class ConsoleAccess {
        void log() { System.out.println("not a core responsibility"); }
    }

    static class ParallelStream {
        long count(List<String> values) { return values.parallelStream().count(); }
    }

    static class PureCode {
        long deadline(MonotonicClock clock, RandomSource random) {
            return Math.addExact(clock.nowNanos(), random.nextLong(1, 10));
        }

        long duration() { return Duration.ofMillis(5).toNanos(); }

        void copy(byte[] source, byte[] target) { System.arraycopy(source, 0, target, 0, source.length); }
    }
}
