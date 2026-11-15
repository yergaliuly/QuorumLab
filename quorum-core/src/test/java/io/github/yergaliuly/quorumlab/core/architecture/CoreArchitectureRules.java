package io.github.yergaliuly.quorumlab.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

final class CoreArchitectureRules {
    private CoreArchitectureRules() {
    }

    private static final String CORE = "io.github.yergaliuly.quorumlab.core..";

    static final ArchRule OWN_PACKAGE = classes().should().resideInAPackage(CORE);

    static final ArchRule JDK_ONLY = classes().should().onlyDependOnClassesThat()
            .resideInAnyPackage(CORE, "java..");

    private static final Set<String> IMPURE_TYPES = Set.of(
            "java.lang.Thread", "java.lang.ThreadGroup", "java.lang.Process",
            "java.lang.ProcessBuilder", "java.lang.Runtime", "java.time.Clock",
            "java.util.Date", "java.util.Calendar", "java.util.GregorianCalendar",
            "java.util.Timer", "java.util.TimerTask", "java.util.Random",
            "java.util.SplittableRandom", "java.security.SecureRandom");

    private static final List<String> IMPURE_PACKAGES = List.of(
            "java.io.", "java.nio.", "java.net.", "java.sql.",
            "java.util.concurrent.", "java.util.logging.", "java.util.random.");

    static final ArchRule NO_IO_THREADS_OR_RANDOM = noClasses().should().dependOnClassesThat(
            new DescribedPredicate<>("perform I/O, create concurrency or obtain ambient time/randomness") {
                @Override
                public boolean test(JavaClass target) {
                    return IMPURE_TYPES.contains(target.getName())
                            || IMPURE_PACKAGES.stream().anyMatch(target.getName()::startsWith);
                }
            });

    static final ArchRule NO_AMBIENT_ACCESS = classes().should(
            new ArchCondition<>("avoid ambient state APIs, including method references") {
                @Override
                public void check(JavaClass item, ConditionEvents events) {
                    // A method reference can escape a rule that only inspects method calls.
                    Stream.concat(item.getMethodCallsFromSelf().stream(),
                            item.getMethodReferencesFromSelf().stream()).forEach(access -> {
                                if (forbiddenMethod(access)) {
                                    events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                                }
                            });
                    item.getFieldAccessesFromSelf().stream()
                            .filter(access -> access.getTargetOwner().getName().equals("java.lang.System"))
                            .forEach(access -> events.add(
                                    SimpleConditionEvent.violated(access, access.getDescription())));
                }
            });

    private static boolean forbiddenMethod(JavaAccess<?> access) {
        String owner = access.getTargetOwner().getName();
        String method = access.getTarget().getName();
        if (owner.equals("java.lang.System")) {
            // Array copying is pure; clocks, properties, environment, console and VM control are not.
            return !method.equals("arraycopy");
        }
        return (owner.equals("java.lang.Math") && method.equals("random"))
                || (owner.equals("java.util.UUID") && method.equals("randomUUID"))
                || (owner.startsWith("java.time.") && method.equals("now"))
                || (owner.startsWith("java.util.")
                    && (method.equals("parallel") || method.equals("parallelStream")));
    }

    static List<ArchRule> all() {
        return List.of(OWN_PACKAGE, JDK_ONLY, NO_IO_THREADS_OR_RANDOM, NO_AMBIENT_ACCESS);
    }
}
