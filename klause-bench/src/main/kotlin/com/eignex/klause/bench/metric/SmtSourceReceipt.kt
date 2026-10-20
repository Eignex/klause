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
    val memory: SourceToolMemory? = null,
)

internal object SmtSourceReceipt {
    private const val MAX_LOG_BYTES = 1024 * 1024

    fun run(
        arguments: List<String>,
        input: File,
        directory: File,
        timeoutMs: Long,
        residentLimitBytes: Long = SourceToolMemory.LIMIT,
    ): SmtToolProcess {
        require(timeoutMs in 1..35_000)
        require(residentLimitBytes in 1..SourceToolMemory.LIMIT)
        val host = System.getProperty("os.name")
        val memory = if (host == "Mac OS X") DarwinToolMemory(directory, residentLimitBytes) else null
        val command = when (host) {
            "Linux" -> listOf("/usr/bin/timeout", "--signal=KILL", "30s", "/usr/bin/prlimit",
                "--as=4294967296", "--cpu=30", "--fsize=1048576", "--core=0", "--") + arguments

            "Mac OS X" -> listOf("/bin/sh", "-c",
                "ulimit -t 30 && ulimit -f 1024 && ulimit -c 0 && exec \"\$@\"", "smt-source-tool",
                "/usr/bin/time", "-l", "-o", checkNotNull(memory).timingFile.absolutePath) + arguments

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
            while (child.isAlive) {
                val remaining = timeoutMs - (System.nanoTime() - started) / 1_000_000
                if (remaining <= 0) {
                    timedOut = true
                    break
                }
                if (memory != null && !memory.sample(child)) break
                child.waitFor(minOf(25, remaining), TimeUnit.MILLISECONDS)
            }
            if (!child.isAlive) exit = child.exitValue()
        } finally {
            synchronized(gate) {
                cleanupComplete = process?.let(::stop) ?: true
                if (cleanupComplete && exit == null) exit = process?.exitValue()
                runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
            }
        }
        fun read(file: File): String {
            val bytes = file.inputStream().use { it.readNBytes(MAX_LOG_BYTES + 1) }
            check(bytes.size < MAX_LOG_BYTES) { "checker output reached its limit" }
            return bytes.decodeToString(throwOnInvalidSequence = true)
        }
        val control = memory?.finish() ?: SourceToolMemory(SourceToolMemory.LINUX, SourceToolMemory.LIMIT,
            complete = true, exceeded = false)
        return SmtToolProcess(command, exit, (System.nanoTime() - started) / 1_000_000,
            timedOut, cleanupComplete, read(stdout), read(stderr), control)
    }

    private fun stop(process: Process): Boolean {
        val interrupted = Thread.interrupted()
        return try {
            val deadline = System.nanoTime() + 5_000_000_000
            val children = process.descendants().use { it.toList() }
            children.asReversed().forEach { it.destroyForcibly() }
            // The timing parent reaps its child and writes the kernel high-water observation.
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
            val reaped = process.waitFor(remaining, TimeUnit.MILLISECONDS)
            while (children.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(10)
            reaped && children.none { it.isAlive }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
