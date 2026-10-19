package com.eignex.klause.bench.metric

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

@Serializable
internal data class SmtToolProcess(
    val command: List<String>,
    val exit: Int?,
    val elapsedMs: Long,
    val timedOut: Boolean,
    val cleanupComplete: Boolean,
    val stdout: String,
    val stderr: String,
)

internal object SmtSourceReceipt {
    private const val MAX_LOG_BYTES = 1024 * 1024

    fun run(arguments: List<String>, input: File, directory: File, timeoutMs: Long): SmtToolProcess {
        require(timeoutMs in 1..35_000)
        val command = when (System.getProperty("os.name")) {
            "Linux" -> listOf("/usr/bin/timeout", "--signal=KILL", "30s", "/usr/bin/prlimit",
                "--as=4294967296", "--fsize=1048576", "--core=0", "--") + arguments

            "Mac OS X" -> listOf("/bin/sh", "-c",
                "ulimit -t 30 && ulimit -f 1024 && ulimit -c 0 && exec \"\$@\"", "smt-source-tool") + arguments

            else -> error("unsupported SMT checker resource controls")
        }
        val stdout = File(directory, "check.stdout")
        val stderr = File(directory, "check.stderr")
        val started = System.nanoTime()
        var process: Process? = null
        var exit: Int? = null
        var timedOut = false
        var cleanupComplete = false
        val gate = Any()
        var stopping = false
        val shutdown = Thread {
            synchronized(gate) {
                stopping = true
                process?.let(::stop)
            }
        }
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            val child = synchronized(gate) {
                check(!stopping && !Thread.currentThread().isInterrupted) { "checker cancelled" }
                ProcessBuilder(command).directory(directory).redirectInput(input)
                    .redirectOutput(stdout).redirectError(stderr).start().also { process = it }
            }
            if (child.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) exit = child.exitValue() else timedOut = true
        } finally {
            synchronized(gate) {
                cleanupComplete = process?.let(::stop) ?: true
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            }
        }
        fun read(file: File): String {
            val bytes = file.inputStream().use { it.readNBytes(MAX_LOG_BYTES + 1) }
            check(bytes.size < MAX_LOG_BYTES) { "checker output reached its limit" }
            return bytes.decodeToString(throwOnInvalidSequence = true)
        }
        return SmtToolProcess(command, exit, (System.nanoTime() - started) / 1_000_000,
            timedOut, cleanupComplete, read(stdout), read(stderr))
    }

    private fun stop(process: Process): Boolean {
        val interrupted = Thread.interrupted()
        return try {
            val children = process.descendants().use { it.toList() }
            children.asReversed().forEach { it.destroyForcibly() }
            if (process.isAlive) process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS) && children.none { it.isAlive }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
