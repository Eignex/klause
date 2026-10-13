package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.source.CorpusFetcher
import kotlinx.serialization.Serializable
import java.io.File
import java.util.concurrent.TimeUnit

internal class SmtStreamChecker(private val output: File) {
    private val tools = File(CorpusFetcher.workspaceRoot(), "klause-bench/tools")
    private val environment = File(output, "source-check-environment")
    private var prepared = false
    private var attempted = false

    fun check(directory: File, source: File, interrupted: Boolean, minimumWitnesses: Int, provision: Boolean) {
        prepare(provision)
        val command = listOf(File(environment, "bin/python").path,
            File(tools, "verify_stream_source_results.py").path, source.path, File(directory, "stdout").path,
            "--minimum-witnesses", minimumWitnesses.toString()) +
            if (interrupted) listOf("--interrupted") else emptyList()
        val execution = execute(command, File(directory, "source-check.stdout"), File(directory, "source-check.stderr"))
        Reports.writeJson(File(directory, "source-check-execution.json").path, execution)
    }

    private fun prepare(provision: Boolean) {
        if (prepared) return
        check(!attempted) { "Source checker setup failed earlier; inspect its setup records" }
        attempted = true
        output.mkdirs()
        val venv = listOf("python3", "-m", "venv", environment.path)
        var created = setup("venv", venv)
        if (created.exitCode != 0 && provision) {
            requireSuccess(setup("apt-update", listOf("sudo", "apt-get", "update")))
            requireSuccess(setup("apt-install", listOf("sudo", "apt-get", "install", "-y", "python3-venv")))
            created = setup("venv-provisioned", venv)
        }
        requireSuccess(created)
        requireSuccess(setup("pip", listOf(File(environment, "bin/pip").path,
            "install", "-r", File(tools, "requirements-source-checks.txt").path)))
        val python = File(environment, "bin/python")
        val version = setup("python-version", listOf(python.path, "--version"))
        val packages = setup("packages", listOf(python.path, "-m", "pip", "freeze"))
        requireSuccess(version)
        requireSuccess(packages)
        val checkerFiles = listOf("verify_stream_source_results.py", "verify_nra_source_results.py",
            "verify_source_results.py", "requirements-source-checks.txt")
        Reports.writeJson(File(output, "source-check-provenance.json").path, CheckerProvenance(
            pythonSha256 = sha256(python.canonicalFile.readBytes()),
            pythonVersion = File(output, "python-version.stdout").readText().trim(),
            packages = File(output, "packages.stdout").readText().trim(),
            sourceHashes = checkerFiles.associateWith { sha256(File(tools, it).readBytes()) },
        ))
        prepared = true
    }

    private fun setup(name: String, command: List<String>): CheckerExecution {
        val result = execute(command, File(output, "$name.stdout"), File(output, "$name.stderr"))
        Reports.writeJson(File(output, "$name.json").path, result)
        return result
    }

    private fun requireSuccess(execution: CheckerExecution) {
        check(execution.exitCode == 0 && !execution.timedOut) { "Source checker setup failed: ${execution.command}" }
    }

    private fun execute(command: List<String>, stdout: File, stderr: File): CheckerExecution {
        val process = ProcessBuilder(command).redirectOutput(stdout).redirectError(stderr).start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        return CheckerExecution(command, if (process.isAlive) null else process.exitValue(), !finished,
            stdout.takeIf(File::isFile)?.readBytes()?.let(::sha256),
            stderr.takeIf(File::isFile)?.readBytes()?.let(::sha256))
    }
}

@Serializable
private data class CheckerExecution(
    val command: List<String>, val exitCode: Int?, val timedOut: Boolean,
    val stdoutSha256: String?, val stderrSha256: String?,
)

@Serializable
private data class CheckerProvenance(
    val pythonSha256: String, val pythonVersion: String, val packages: String, val sourceHashes: Map<String, String>,
)
