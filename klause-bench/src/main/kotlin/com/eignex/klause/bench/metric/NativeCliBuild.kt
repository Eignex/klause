package com.eignex.klause.bench.metric

import com.eignex.klause.bench.source.CorpusFetcher
import java.io.File
import java.security.MessageDigest

internal object NativeCliBuild {
    fun executable(root: File = CorpusFetcher.workspaceRoot()): File =
        root.resolve("klause-cli/build/bin/linuxX64/releaseExecutable/klause-cli.kexe")

    fun capture(
        executable: File = executable(),
        libraries: List<File> = libraries(executable),
        harness: Map<String, String>,
        environment: Map<String, String> = System.getenv(),
    ): BuildProvenance {
        require(executable.isFile) { "Missing Native CLI: $executable" }
        require(libraries.all { it.isFile }) { "Missing Native runtime dependency" }
        require(harness.isNotEmpty()) { "Native qualification requires the executing harness identity" }
        return BuildProvenance(
            distribution = mapOf(executable.name to hash(executable)),
            runtime = libraries.map(File::getCanonicalFile).distinct().sortedBy(File::getPath)
                .associate { it.path to hash(it) },
            runtimeOptions = runtimeOptions(environment).filterKeys { it in setOf("KLAUSE_DEBUG", "LD_LIBRARY_PATH") } +
                ("cliRuntime" to "kotlin-native-linux-x86_64"),
            osName = System.getProperty("os.name"),
            osArch = System.getProperty("os.arch"),
            harness = harness,
        )
    }

    fun libraryPaths(output: String): List<File> {
        require("not found" !in output) { "Unresolved Native runtime dependency" }
        return output.lineSequence().mapNotNull { line ->
            LIBRARY_PATH.find(line)?.groupValues?.get(1)?.let(::File)
        }.distinct().toList()
    }

    fun harnessIdentity(root: File = CorpusFetcher.workspaceRoot()): Map<String, String> = buildMap {
        val trees = mapOf(
            "bench" to root.resolve("klause-bench/build/install/klause-bench"),
            "jvm" to File(System.getProperty("java.home")),
        )
        for ((name, tree) in trees) {
            require(tree.isDirectory) { "Missing Native qualification harness: $tree" }
            for (file in tree.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(tree).path }) {
                put("$name/${file.relativeTo(tree).invariantSeparatorsPath}", hash(file))
            }
        }
    }

    fun treeIdentity(tree: File): Map<String, String> {
        require(tree.isDirectory) { "Missing caller runtime: $tree" }
        return tree.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(tree).path }
            .associate { it.relativeTo(tree).invariantSeparatorsPath to hash(it) }
    }

    private fun libraries(executable: File): List<File> {
        require(System.getProperty("os.name") == "Linux") { "Native CLI qualification requires Linux" }
        val process = ProcessBuilder("timeout", "10", "ldd", executable.canonicalPath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        require(process.waitFor() == 0) { "Cannot inspect Native runtime dependencies: $output" }
        return libraryPaths(output).also { require(it.isNotEmpty()) { "Native runtime dependencies are unavailable" } }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private val LIBRARY_PATH = Regex("(?:=>\\s+)?(/\\S+)\\s+\\(")
}
