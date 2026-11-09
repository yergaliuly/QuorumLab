import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.wrapper.Wrapper

plugins {
    base
}

allprojects {
    group = "io.github.yergaliuly.quorumlab"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.14.4"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

// Explicit aggregate tasks keep root commands useful as more modules gain code.
tasks.named("clean") {
    dependsOn(subprojects.map { it.tasks.named("clean") })
}

tasks.register("test") {
    group = "verification"
    description = "Runs the tests in every Java module."
    dependsOn(subprojects.map { it.tasks.named("test") })
}

tasks.named("check") {
    dependsOn(subprojects.map { it.tasks.named("check") })
}

tasks.named("build") {
    dependsOn(subprojects.map { it.tasks.named("build") })
}

tasks.named<Wrapper>("wrapper") {
    gradleVersion = "9.9.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "0b00b0ffe07c4d4789c12d4935a44a7057d55774b7540bd4a3f76f336deb177a"
    networkTimeout = 60_000
}
