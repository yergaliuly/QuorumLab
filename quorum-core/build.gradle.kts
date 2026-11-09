import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test

description = "Pure Java Raft protocol: events, state transitions, and effects."

dependencies {
    "testImplementation"("com.tngtech.archunit:archunit:1.5.1")
}

val mainClassDirs = the<SourceSetContainer>()["main"].output.classesDirs
val productionClasspaths = listOf(
    configurations.named("compileClasspath"),
    configurations.named("runtimeClasspath"),
)

val verifyCoreClasspath by tasks.registering {
    group = "verification"
    description = "Rejects all non-JDK compile/runtime dependencies in quorum-core."
    productionClasspaths.forEach { inputs.files(it) }
    doLast {
        val forbidden = productionClasspaths.flatMap { it.get().files }.distinct()
        check(forbidden.isEmpty()) {
            "quorum-core production classpaths must be empty (JDK only). " +
                "Move dependencies to adapters or testImplementation: " +
                forbidden.joinToString { it.name }
        }
    }
}

tasks.named<Test>("test") {
    dependsOn(verifyCoreClasspath)
    // Import every production class, including classes outside the expected package.
    systemProperty("quorum.core.classes", mainClassDirs.asPath)
}
