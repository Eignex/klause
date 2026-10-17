package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

internal data class CompilationStudyBuild(val arm: String, val root: File, val bench: File, val provenance: File)

internal class CompilationStudyBuilds(private val root: File, private val out: File) : AutoCloseable {
    private val workspace = Files.createTempDirectory("klause-compilation-study-").toFile()
    private val created = mutableListOf<File>()
    private val receipts = mutableListOf<CompilationStudyBuildReceipt>()

    fun prepare(heads: Map<String, String>): Map<String, CompilationStudyBuild> {
        validateHeads(heads)
        return listOf("control", "candidate").associateWith { arm ->
            val head = heads.getValue(arm)
            val tree = File(workspace, arm)
            command(root, out, "$arm-fetch", listOf("git", "fetch", "origin", head))
            // Register ownership before Git can leave a partially created checkout.
            created += tree
            command(root, out, "$arm-worktree", listOf("git", "worktree", "add", "--detach", tree.path, head))
            val actual = command(tree, out, "$arm-source", listOf("git", "rev-parse", "HEAD")).trim()
            check(actual == head)
            check(command(tree, out, "$arm-clean-before", listOf("git", "status", "--porcelain")).isBlank())
            val invocation = File(tree,
                "klause-bench/src/main/kotlin/com/eignex/klause/bench/metric/SolverInvocation.kt")
            check("KLAUSE_CLI_PREFLIGHT_OPTS" in invocation.readText()) {
                "Pinned harness lacks separate CLI preflight options: $head"
            }
            command(tree, out, "$arm-build", listOf("./gradlew", ":klause-cli:installJvmDist",
                ":klause-bench:installDist", "--max-workers=2", "--no-daemon",
                "-Pkotlin.compiler.execution.strategy=in-process"))
            check(command(tree, out, "$arm-clean-after", listOf("git", "status", "--porcelain")).isBlank())
            val launcher = File(tree, "klause-cli/build/install/klause-cli-jvm/bin/klause-cli")
            val bench = File(tree, "klause-bench/build/install/klause-bench/bin/klause-bench")
            check(launcher.canExecute() && bench.canExecute())
            val provenance = File(out, "$arm-provenance.json")
            InstalledBuild.writeManifest(provenance, launcher, File(System.getProperty("java.home")))
            val harness = File(out, "$arm-harness-provenance.json")
            InstalledBuild.writeManifest(harness, bench, File(System.getProperty("java.home")))
            val retained = listOf("cli" to launcher, "harness" to bench).associate { (name, executable) ->
                name to CompilationStudyDistributions.retain(executable.parentFile.parentFile,
                    File(out, "private-installed/$arm/$name"))
            }
            Reports.writeJson(File(out, "$arm-retained-distributions.json").path, retained)
            InstalledBuild.readManifest(provenance, launcher, File(System.getProperty("java.home")), emptyMap())
            InstalledBuild.readManifest(harness, bench, File(System.getProperty("java.home")), emptyMap())
            receipts += CompilationStudyBuildReceipt(arm, head, actual, tree.path, launcher.path,
                sha256(provenance.readBytes()), sha256(harness.readBytes()))
            Reports.writeJson(File(out, "study-builds.json").path, receipts)
            CompilationStudyBuild(arm, tree, bench, provenance)
        }
    }

    override fun close() {
        val failures = mutableListOf<Throwable>()
        for ((index, tree) in created.withIndex().reversed()) {
            if (!tree.exists()) continue
            runCatching {
                command(root, out, "cleanup-$index", listOf("git", "worktree", "remove", "--force", tree.path))
            }.onFailure(failures::add)
        }
        if (failures.isEmpty()) check(workspace.delete()) { "Cannot remove owned study workspace" }
        if (failures.isNotEmpty()) {
            val failure = IllegalStateException("Owned study worktree cleanup failed")
            failures.forEach(failure::addSuppressed)
            throw failure
        }
    }

    companion object {
        private const val COMMAND_MINUTES = 90L

        fun validateHeads(heads: Map<String, String>) {
            require(heads.keys == setOf("control", "candidate"))
            require(heads.values.all { it.matches(Regex("[0-9a-f]{40}")) })
        }

        fun command(
            root: File,
            out: File,
            name: String,
            arguments: List<String>,
            requireSuccess: Boolean = true,
        ): String {
            val stdout = File(out, "$name.stdout")
            val builder = ProcessBuilder(arguments).directory(root).redirectOutput(stdout)
                .redirectError(File(out, "$name.stderr"))
            for (setting in listOf("JAVA_OPTS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
                builder.environment().remove(setting)
            }
            builder.environment()["JAVA_HOME"] = System.getProperty("java.home")
            val process = builder.start()
            val finished = process.waitFor(COMMAND_MINUTES, TimeUnit.MINUTES)
            if (!finished) kill(process)
            File(out, "$name.exit").writeText("${process.exitValue()}\n")
            check(finished && (!requireSuccess || process.exitValue() == 0)) { "Study command failed: $name" }
            return if (name.endsWith("-source") || name.startsWith("cpu-") || "-clean-" in name) {
                stdout.readText()
            } else ""
        }

        fun kill(process: Process) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly().waitFor()
        }
    }
}

@Serializable
private data class CompilationStudyBuildReceipt(
    val arm: String,
    val expectedHead: String,
    val actualHead: String,
    val root: String,
    val cli: String,
    val provenanceSha256: String,
    val harnessProvenanceSha256: String,
)
