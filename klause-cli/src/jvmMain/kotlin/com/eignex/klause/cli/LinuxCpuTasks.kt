package com.eignex.klause.cli

import java.nio.file.Files
import java.nio.file.Path

internal data class LinuxTaskCpu(val tid: Long, val name: String, val startTicks: Long, val cpuTicks: Long)

internal data class LinuxCpuTasks(val tasks: List<LinuxTaskCpu>, val readFailures: Int)

internal fun parseLinuxTaskCpu(text: String): LinuxTaskCpu? {
    val open = text.indexOf('(')
    val close = text.lastIndexOf(')')
    if (open < 1 || close <= open) return null
    val tid = text.substring(0, open).trim().toLongOrNull()?.takeIf { it > 0L } ?: return null
    val fields = text.substring(close + 1).trim().split(Regex("\\s+"))
    if (fields.size < 20) return null
    val user = fields[11].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    val system = fields[12].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    val start = fields[19].toLongOrNull()?.takeIf { it >= 0L } ?: return null
    if (user > Long.MAX_VALUE - system) return null
    return LinuxTaskCpu(tid, text.substring(open + 1, close), start, user + system)
}

internal fun readLinuxCpuTasks(): LinuxCpuTasks? = runCatching {
    var failures = 0
    val tasks = mutableListOf<LinuxTaskCpu>()
    Files.newDirectoryStream(Path.of("/proc/self/task")).use { entries ->
        for (entry in entries) {
            val task = runCatching { parseLinuxTaskCpu(Files.readString(entry.resolve("stat"))) }.getOrNull()
            if (task == null || task.tid.toString() != entry.fileName.toString()) failures++ else tasks.add(task)
        }
    }
    LinuxCpuTasks(tasks.sortedBy { it.tid }, failures)
}.getOrNull()
