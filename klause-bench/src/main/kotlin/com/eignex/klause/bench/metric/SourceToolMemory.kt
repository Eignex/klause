package com.eignex.klause.bench.metric

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

@Serializable
internal data class SourceToolMemory(
    val policy: String,
    val limitBytes: Long,
    val complete: Boolean,
    val exceeded: Boolean,
    val samplePeakBytes: Long? = null,
    val kernelPeakBytes: Long? = null,
    val samples: Int = 0,
    val timingSha256: String? = null,
) {
    fun qualified(): Boolean = complete && !exceeded && limitBytes in 1..LIMIT && when (policy) {
        LINUX -> samplePeakBytes == null && kernelPeakBytes == null
        DARWIN -> kernelPeakBytes != null && kernelPeakBytes in 1..limitBytes && samples >= 0 &&
            ((samples == 0 && samplePeakBytes == null) ||
                (samples > 0 && samplePeakBytes != null && samplePeakBytes in 1..limitBytes)) &&
            timingSha256?.matches(Regex("[0-9a-f]{64}")) == true
        else -> false
    }

    companion object {
        const val LIMIT = 4_294_967_296L
        const val LINUX = "linux-address-space-v1"
        const val DARWIN = "darwin-sampled-rss-wait4-v1"
    }
}

internal class DarwinToolMemory(directory: File, private val limitBytes: Long) {
    val timingFile = File(directory, "memory.time")
    private val output = File(directory, "memory.sample")
    private val errors = File(directory, "memory.stderr")
    private var complete = true
    private var peak: Long? = null
    private var samples = 0
    private var exceeded = false

    fun sample(process: Process): Boolean {
        val descendants = process.descendants().use { it.limit(1024).toList() }
        if (descendants.size >= 1024) {
            complete = false
            return false
        }
        val handles = listOf(process.toHandle()) + descendants
        val pids = handles.map { it.pid() }.toSet()
        val probe = ProcessBuilder("/bin/ps", "-o", "pid=,rss=", "-p", pids.joinToString(","))
            .redirectOutput(output).redirectError(errors).start()
        try {
            if (!probe.waitFor(1, TimeUnit.SECONDS)) {
                complete = false
                return false
            }
            val text = bounded(output) ?: return false.also { complete = false }
            if (bounded(errors)?.isEmpty() != true) return false.also { complete = false }
            if (probe.exitValue() != 0 || text.isBlank()) {
                if (process.isAlive) complete = false
                return !process.isAlive
            }
            var bytes = 0L
            val seen = HashSet<Long>()
            for (line in text.lineSequence().filter { it.isNotBlank() }) {
                val columns = line.trim().split(Regex("\\s+"))
                val pid = columns.getOrNull(0)?.toLongOrNull()
                val kib = columns.getOrNull(1)?.toLongOrNull()
                if (columns.size != 2 || pid == null || pid !in pids || !seen.add(pid) ||
                    kib == null || kib < 0 || kib > (Long.MAX_VALUE - bytes) / 1024
                ) {
                    complete = false
                    return false
                }
                bytes += kib * 1024
            }
            if (bytes <= 0 || handles.any { it.isAlive && it.pid() !in seen }) {
                complete = false
                return false
            }
            samples++
            peak = maxOf(peak ?: 0, bytes)
            exceeded = bytes > limitBytes
            return !exceeded
        } finally {
            if (probe.isAlive) probe.destroyForcibly()
            if (!probe.waitFor(1, TimeUnit.SECONDS)) complete = false
        }
    }

    fun finish(): SourceToolMemory {
        val text = bounded(timingFile)
        val peaks = text?.lineSequence()?.mapNotNull {
            Regex("\\s*([0-9]+)\\s+maximum resident set size\\s*").matchEntire(it)
                ?.groupValues?.get(1)?.toLongOrNull()
        }?.toList().orEmpty()
        val kernelPeak = peaks.singleOrNull()?.takeIf { it > 0 }
        return SourceToolMemory(SourceToolMemory.DARWIN, limitBytes, complete && kernelPeak != null,
            exceeded || (kernelPeak != null && kernelPeak > limitBytes), peak, kernelPeak, samples,
            text?.toByteArray()?.let(::sha256))
    }

    private fun bounded(file: File): String? = if (!file.isFile) null else {
        val bytes = file.inputStream().use { it.readNBytes(65_537) }
        if (bytes.size > 65_536) null else bytes.decodeToString(throwOnInvalidSequence = true)
    }
}
