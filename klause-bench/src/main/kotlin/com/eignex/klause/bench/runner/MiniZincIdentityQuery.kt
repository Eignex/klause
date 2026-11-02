package com.eignex.klause.bench.runner

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

@Serializable
internal data class MiniZincIdentityEvidence(
    val phase: String,
    val command: List<String>,
    val startedAt: String,
    val outcome: String,
    val elapsedNs: Long,
    val startupNs: Long? = null,
    val waitNs: Long? = null,
    val cleanupNs: Long = 0,
    val timeoutNs: Long,
    val executableSha256: String? = null,
    val randomSeed: Int = MZN_RANDOM_SEED,
    val pid: Long? = null,
    val exitCode: Int? = null,
    val cpuNs: Long? = null,
    val cancelled: Boolean = false,
    val aliveAfterCleanup: Boolean = false,
    val diagnostic: String = "",
    val diagnosticBytes: Long = 0,
    val diagnosticComplete: Boolean = false,
    val policy: String = "minizinc-identity-query-v1",
)

internal class MiniZincIdentityFailure(
    val evidence: MiniZincIdentityEvidence,
    cause: Throwable? = null,
) : IllegalStateException(
    "MiniZinc identity ${evidence.phase} ${evidence.outcome} after ${evidence.elapsedNs} ns" +
        " (exit=${evidence.exitCode}, cancelled=${evidence.cancelled}): ${evidence.diagnostic}",
    cause,
)

internal class MiniZincIdentityQuery(
    private val executableSha256: String? = null,
    private val timeoutNs: Long = TimeUnit.SECONDS.toNanos(10),
    private val clock: () -> Long = System::nanoTime,
    private val start: (List<String>, File) -> Process = { command, log ->
        ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
    },
    private val retain: (MiniZincIdentityEvidence) -> Unit = {
        System.err.println(Reports.json.encodeToString(it))
    },
) {
    fun query(executable: File, option: String): String {
        val began = clock()
        val initial = MiniZincIdentityEvidence(
            phase = option,
            command = listOf(executable.path, option),
            startedAt = Instant.now().toString(),
            outcome = "starting",
            elapsedNs = 0,
            timeoutNs = timeoutNs,
            executableSha256 = executableSha256,
        )
        retain(initial)
        var log: File? = null
        var process: Process? = null
        var startupNs: Long? = null
        var waitNs: Long? = null
        var cpuNs: Long? = null
        var outcome = "start-failed"
        var cause: Throwable? = null
        var cancelled = false
        var cleanupNs = 0L
        try {
            try {
                if (Thread.currentThread().isInterrupted) throw InterruptedException("Identity discovery cancelled")
                log = File.createTempFile("minizinc-identity-", ".log")
                val child = start(initial.command, log)
                process = child
                startupNs = clock() - began
                outcome = "query-failed"
                retain(initial.copy(
                    outcome = "process-started",
                    elapsedNs = startupNs,
                    startupNs = startupNs,
                    pid = runCatching { child.pid() }.getOrNull(),
                ))
                val remaining = timeoutNs - startupNs
                val waitStart = clock()
                try {
                    outcome = if (remaining <= 0 || !child.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                        "timed-out"
                    } else if (child.exitValue() == 0) {
                        "completed"
                    } else {
                        "exited"
                    }
                } finally {
                    waitNs = clock() - waitStart
                }
            } catch (failure: InterruptedException) {
                cause = failure
                cancelled = true
                outcome = "cancelled"
                // Cleanup must still be able to wait after a cancellation before restoring the flag.
                Thread.interrupted()
            } catch (failure: Exception) {
                cause = failure
            } finally {
                val cleanupStart = clock()
                val child = process
                if (child != null) {
                    cpuNs = runCatching { child.info().totalCpuDuration().orElse(null)?.toNanos() }.getOrNull()
                    if (child.isAlive) {
                        runCatching {
                            child.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
                        }
                        child.destroyForcibly()
                        try {
                            child.waitFor(2, TimeUnit.SECONDS)
                        } catch (failure: InterruptedException) {
                            cancelled = true
                            outcome = "cancelled"
                            if (cause == null) cause = failure
                            Thread.interrupted()
                        }
                    }
                }
                cleanupNs = clock() - cleanupStart
            }
            val bytes = log?.takeIf(File::isFile)?.length() ?: 0
            val diagnostic = log?.takeIf(File::isFile)?.inputStream()?.use { it.readNBytes(OUTPUT_LIMIT) }
                ?.toString(Charsets.UTF_8).orEmpty()
            val deleteStart = clock()
            log?.delete()
            cleanupNs += clock() - deleteStart
            val alive = process?.isAlive == true
            val evidence = initial.copy(
                outcome = if (outcome == "completed" && bytes > OUTPUT_LIMIT) "output-truncated" else outcome,
                elapsedNs = clock() - began,
                startupNs = startupNs,
                waitNs = waitNs,
                cleanupNs = cleanupNs,
                pid = process?.let { runCatching { it.pid() }.getOrNull() },
                exitCode = process?.takeUnless { it.isAlive }?.exitValue(),
                cpuNs = cpuNs,
                cancelled = cancelled,
                aliveAfterCleanup = alive,
                diagnostic = diagnostic.ifEmpty { cause?.message.orEmpty() },
                diagnosticBytes = bytes,
                diagnosticComplete = outcome in setOf("completed", "exited") && bytes <= OUTPUT_LIMIT && !alive,
            )
            retain(evidence)
            if (outcome != "completed" || !evidence.diagnosticComplete) throw MiniZincIdentityFailure(evidence, cause)
            return diagnostic
        } finally {
            log?.delete()
            if (cancelled) Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val OUTPUT_LIMIT = 16384
    }
}
