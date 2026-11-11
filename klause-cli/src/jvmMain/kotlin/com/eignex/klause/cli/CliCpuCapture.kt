package com.eignex.klause.cli

import java.lang.management.ManagementFactory
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val CLI_CPU_CAPTURE_ENABLED = true

internal actual fun <T> captureCliCpu(operation: () -> T): T =
    captureCliCpuIf(CLI_CPU_CAPTURE_ENABLED, operation)

internal fun <T> captureCliCpuIf(enabled: Boolean, operation: () -> T): T {
    if (!enabled) return operation()
    val capture = runCatching { CliCpuInvocation() }.getOrNull()
    return try { operation() } finally {
        if (capture == null) System.err.println("klause-cli-cpu-v1 unavailable=setup")
        else runCatching { capture.finish() }.onFailure {
            System.err.println("klause-cli-cpu-v1 unavailable=finish")
        }
    }
}

private class CliCpuInvocation {
    private val threads = ManagementFactory.getThreadMXBean()
    private val process = ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
    private val threadId = Thread.currentThread().threadId()
    private val tickHz = linuxTickHz()
    private val beforeTasks = readLinuxCpuTasks()
    private val beforeProcess = processCpu()
    private val beforeThread = threadCpu()

    fun finish() {
        val afterThread = threadCpu()
        val afterProcess = processCpu()
        val afterTasks = readLinuxCpuTasks()
        val sameThread = threadId == Thread.currentThread().threadId()
        System.err.println("klause-cli-cpu-v1 tickHz=$tickHz sameThread=$sameThread threadId=$threadId " +
            "beforeProcessNs=$beforeProcess afterProcessNs=$afterProcess " +
            "beforeThreadNs=$beforeThread afterThreadNs=$afterThread " +
            "beforeFailures=${beforeTasks?.readFailures ?: -1} afterFailures=${afterTasks?.readFailures ?: -1} " +
            "beforeTasks=${encodeTasks(beforeTasks)} afterTasks=${encodeTasks(afterTasks)}")
    }

    private fun processCpu(): Long = runCatching { process?.processCpuTime ?: -1L }.getOrDefault(-1L)

    private fun threadCpu(): Long = if (threads.isCurrentThreadCpuTimeSupported && threads.isThreadCpuTimeEnabled) {
        threads.currentThreadCpuTime
    } else -1L
}

private fun encodeTasks(snapshot: LinuxCpuTasks?): String = snapshot?.tasks?.joinToString(",") {
    val name = Base64.getEncoder().encodeToString(it.name.toByteArray(Charsets.UTF_8))
    "${it.tid}:${it.startTicks}:${it.cpuTicks}:$name"
} ?: "unsupported"

private fun linuxTickHz(): Long = runCatching {
    val process = ProcessBuilder("getconf", "CLK_TCK").redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        if (!process.waitFor(1L, TimeUnit.SECONDS)) return@runCatching -1L
        if (process.exitValue() != 0) return@runCatching -1L
        process.inputStream.bufferedReader().use {
            it.readText().trim().toLongOrNull()?.takeIf { hz -> hz > 0L } ?: -1L
        }
    } finally {
        if (process.isAlive) process.destroyForcibly()
        process.inputStream.close()
    }
}.getOrDefault(-1L)
