package com.eignex.klause.bench.metric

import com.eignex.klause.bench.catalog.ProblemRef
import com.eignex.klause.bench.runner.Budget
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.klause.bench.source.CorpusFiles
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object Z3Reference {
    private val binary by lazy {
        val requested = System.getProperty("klause.bench.z3", "z3")
        val file = if (File(requested).isAbsolute || File(requested).parent != null) {
            File(requested)
        } else {
            System.getenv("PATH").orEmpty().split(File.pathSeparator)
                .map { File(it, requested) }.firstOrNull { it.isFile && it.canExecute() }
                ?: error("z3 executable not found: $requested")
        }
        file.canonicalFile.also { require(it.isFile && it.canExecute()) { "not executable: $it" } }
    }
    private val memoryMb = System.getProperty("klause.bench.z3.memoryMb", "2048").toInt().also {
        require(it > 0) { "z3 memory limit must be positive" }
    }
    private const val EXIT_WAIT_MS = 5_000L
    private const val POLICY = "smt-reference-v2"
    private val options = listOf("-model", "sat.threads=1", "parallel.enable=false", "pp.decimal=false")

    fun available(): Boolean = runCatching { NativeReference.available(binary.path) }.getOrDefault(false)

    private val build by lazy {
        val proc = ProcessBuilder(binary.path, "--version").redirectErrorStream(true).start()
        try {
            check(proc.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) { "z3 version timed out" }
            check(proc.exitValue() == 0) { "z3 version failed" }
            val version = proc.inputStream.bufferedReader().readText().trim()
            val digest = MessageDigest.getInstance("SHA-256")
            binary.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
            "$version|sha256=$hash"
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }

    fun identity(): String = "$build|memoryMb=$memoryMb|${options.joinToString(",")}|$POLICY"

    fun run(ref: ProblemRef, budget: Budget): SolverInvocation.Result =
        CorpusFiles.withPlainFile(CorpusFetcher.resolve(ref.source)) { file ->
            val identity = identity()
            val cmd = listOf(
                binary.path, "-T:${(budget.timeoutMillis / 1000).coerceAtLeast(1)}", "-memory:$memoryMb", "-smt2",
            ) + options + file.absolutePath
            val startNanos = System.nanoTime()
            val proc = ProcessBuilder(cmd).start()
            proc.outputStream.close()
            val stderr = StringBuilder()
            val drain = Thread { proc.errorStream.bufferedReader().use { stderr.append(it.readText()) } }.apply {
                isDaemon = true
                start()
            }
            val killed = AtomicBoolean(false)
            val watchdog = Thread {
                runCatching {
                    if (!proc.waitFor(budget.timeoutMillis * 2 + 20_000, TimeUnit.MILLISECONDS)) {
                        killed.set(true)
                        proc.destroyForcibly()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            try {
                val stdout = proc.inputStream.bufferedReader().use { it.readText() }
                proc.waitFor()
                drain.join()
                val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                parse(stdout, elapsedMs, cmd, stderr.toString(), proc.exitValue(), killed.get()).let { result ->
                    result.copy(stats = result.stats + mapOf(
                        "referenceVersion" to identity,
                        "referenceBinary" to binary.path,
                        "referenceModelFormat" to "root-obj-requested",
                        "referenceValidation" to "solver-reported",
                    ))
                }
            } finally {
                watchdog.interrupt()
                if (proc.isAlive) proc.destroyForcibly()
            }
        }

    internal fun parse(
        stdout: String,
        elapsedMs: Long,
        cmd: List<String>,
        stderr: String = "",
        exitCode: Int = 0,
        killed: Boolean = false,
    ): SolverInvocation.Result {
        val verdict = stdout.lineSequence().map { it.trim() }
            .lastOrNull { it == "sat" || it == "unsat" || it == "unknown" }
        val error = stdout.lineSequence().any { it.trimStart().startsWith("(error ") }
        val status = when {
            killed || stdout.lineSequence().any { it.trim() == "timeout" } -> "timeout"
            error -> "error"
            exitCode != 0 && verdict != "unknown" -> "error"
            else -> verdict ?: "error"
        }
        val feasible = when (status) {
            "sat" -> true
            "unsat" -> false
            else -> null
        }
        val timeMs = elapsedMs.takeIf { feasible == true }
        return SolverInvocation.Result(
            feasible = feasible,
            objective = null,
            elapsedMs = elapsedMs,
            timeToBestMs = timeMs,
            timeToFirstFeasibleMs = timeMs,
            proven = feasible != null,
            stats = buildMap {
                put("solveTime", (elapsedMs / 1000.0).toString())
                put("maximize", "false")
                put("referenceStatus", status)
                put("referenceExitCode", exitCode.toString())
                put("referenceWatchdogKilled", killed.toString())
                put("referenceStderr", stderr)
                if (status == "error") put("referenceError", "solver output or exit did not establish a verdict")
            },
            rawOutput = stdout,
            command = cmd.joinToString(" "),
        )
    }
}
