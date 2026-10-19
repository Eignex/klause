package com.eignex.klause.bench.metric

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

@Serializable
internal data class MpsNativeProcess(
    val command: List<String>,
    val pid: Long,
    val exit: Int?,
    val stop: String?,
    val cleanupComplete: Boolean,
    val elapsedMs: Long,
    val stdout: String,
    val stderr: String,
    val stdoutSha256: String,
    val stderrSha256: String,
)

internal object MpsSourceReceipt {
    private val summary = Regex("Check SOL: Integrality ([01]) Constraints ([01]) Objective ([01])")

    data class Verdict(val integral: Boolean, val feasible: Boolean, val objective: Boolean)

    fun interpret(process: MpsNativeProcess, coordinates: Int, linear: String): Verdict {
        require(process.exit == 0 && process.stop == null && process.cleanupComplete)
        require(process.stdoutSha256 == sha256(process.stdout.toByteArray()))
        require(process.stderrSha256 == sha256(process.stderr.toByteArray()))
        require(process.stdout.toByteArray().size <= 1_048_576 && process.stderr.toByteArray().size <= 1_048_576)
        require(process.elapsedMs in 0..60_000)
        require(process.stderr.lineSequence().all { it.isEmpty() || it.startsWith("Failed ") ||
            it.startsWith("                ") })
        val lines = process.stdout.lines().filter { it.isNotEmpty() }
        require(lines.size == 9 && lines[0] == "Read MPS: 1" && lines[2] == "Read SOL: 1")
        val census = requireNotNull(Regex("MIP has ([0-9]+) vars and ([0-9]+) constraints").matchEntire(lines[1]))
        require(census.groupValues[1].toInt() == coordinates) { "incomplete source point" }
        require(lines[3].startsWith("Objective value computed by solver: "))
        require(lines[4] == "Integrality tolerance: 0")
        require(lines[5] == "Linear tolerance: $linear" && lines[6] == "Objective tolerance: $linear")
        val checked = requireNotNull(summary.matchEntire(lines[7])).groupValues
        require(Regex("Maximum violations: Integrality \\S+ Constraints \\S+ Objective \\S+").matches(lines[8]))
        return Verdict(checked[1] == "1", checked[2] == "1", checked[3] == "1")
    }

    fun run(command: List<String>, directory: File, timeoutMs: Long = 30_000): MpsNativeProcess {
        require(timeoutMs in 1..30_000)
        val stdout = File(directory, "checker.stdout")
        val stderr = File(directory, "checker.stderr")
        val started = System.nanoTime()
        val gate = Any()
        var active: Process? = null
        val shutdown = Thread { synchronized(gate) { active?.destroyForcibly() } }
        Runtime.getRuntime().addShutdownHook(shutdown)
        var reason: String? = null
        var cleanup = false
        val process: Process
        try {
            process = synchronized(gate) {
                check(!Thread.currentThread().isInterrupted) { "checker cancelled" }
                ProcessBuilder(command).directory(directory).redirectOutput(stdout).redirectError(stderr).start()
                    .also { active = it }
            }
            try {
                while (process.isAlive) {
                    if (stdout.length() > 1_048_576 || stderr.length() > 1_048_576) {
                        reason = "output limit"
                        break
                    }
                    if ((System.nanoTime() - started) / 1_000_000 >= timeoutMs) {
                        reason = "timeout"
                        break
                    }
                    process.waitFor(25, TimeUnit.MILLISECONDS)
                }
            } catch (_: InterruptedException) {
                reason = "cancelled"
                Thread.currentThread().interrupt()
            } finally {
                val interrupted = Thread.interrupted()
                try {
                    process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
                    if (process.isAlive) process.destroyForcibly()
                    cleanup = process.waitFor(5, TimeUnit.SECONDS) && !process.isAlive
                } finally {
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        } finally {
            synchronized(gate) {
                active?.takeIf { it.isAlive }?.destroyForcibly()
                active = null
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            }
        }
        val out = stdout.inputStream().use { it.readNBytes(1_048_577) }.decodeToString(throwOnInvalidSequence = true)
        val err = stderr.inputStream().use { it.readNBytes(1_048_577) }.decodeToString(throwOnInvalidSequence = true)
        return MpsNativeProcess(command, process.pid(), if (cleanup) process.exitValue() else null, reason, cleanup,
            (System.nanoTime() - started) / 1_000_000, out, err, sha256(out.toByteArray()), sha256(err.toByteArray()))
    }
}
