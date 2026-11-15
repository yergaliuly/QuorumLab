package io.github.yergaliuly.quorumlab.core.architecture;

import static org.junit.jupiter.api.Assertions.assertFalse;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CoreArchitectureTest {
    private static JavaClasses productionClasses;

    @BeforeAll
    static void importProductionOutput() {
        String directories = Objects.requireNonNull(System.getProperty("quorum.core.classes"),
                "Run through Gradle: the test requires the production class directories");
        var paths = Arrays.stream(directories.split(Pattern.quote(File.pathSeparator)))
                .map(Path::of).toList();
        productionClasses = new ClassFileImporter().importPaths(paths);
        assertFalse(productionClasses.isEmpty(), "Architecture checks must not pass on an empty import");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void productionCoreRespectsBoundary(ArchRule rule) {
        rule.check(productionClasses);
    }

    static Stream<ArchRule> rules() {
        return CoreArchitectureRules.all().stream();
    }
}
