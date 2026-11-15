description = "WAL, persistent metadata, snapshots, and recovery."

dependencies {
    "api"(project(":quorum-core"))
}

// JUnit's default /tmp may be tmpfs even when the WSL source snapshot is on ext4.
tasks.withType<Test>().configureEach {
    val testFiles = layout.buildDirectory.dir("tmp/test-files")
    systemProperty("java.io.tmpdir", testFiles.get().asFile.absolutePath)
    doFirst { check(testFiles.get().asFile.mkdirs() || testFiles.get().asFile.isDirectory) }
}
