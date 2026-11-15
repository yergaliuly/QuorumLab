description = "Real-process cluster and fault-injection tests."

dependencies {
    "testImplementation"(project(":quorum-core"))
    "testImplementation"(project(":quorum-storage"))
}

// Keep real WAL integration files on the same filesystem as the verified Linux snapshot.
tasks.withType<Test>().configureEach {
    val testFiles = layout.buildDirectory.dir("tmp/test-files")
    systemProperty("java.io.tmpdir", testFiles.get().asFile.absolutePath)
    doFirst { check(testFiles.get().asFile.mkdirs() || testFiles.get().asFile.isDirectory) }
}
