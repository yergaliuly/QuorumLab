import org.gradle.api.initialization.resolve.RepositoriesMode

rootProject.name = "quorumlab"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(
    "quorum-core",
    "quorum-storage",
    "quorum-transport",
    "quorum-kv",
    "quorum-node",
    "quorum-client",
    "quorum-simulator",
    "quorum-checker",
    "quorum-integration",
    "quorum-bench",
)
