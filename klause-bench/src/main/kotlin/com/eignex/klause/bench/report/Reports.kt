package com.eignex.klause.bench.report

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Shared reporting primitives for every metric: environment capture, the serializable result
 * envelopes, and JSON / ns formatting helpers. Each metric owns its per-row result type but
 * reuses [EnvInfo], [Reports.json], and [Reports.writeJson] for a consistent output shape.
 */
object Reports {
    internal val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    internal inline fun <reified T> writeJson(path: String, value: T) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(value))
        println()
        println("wrote $path")
    }

    /** Write a Markdown summary next to the JSON report. */
    fun writeMarkdown(path: String, md: Markdown) {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(md.toString())
        println("wrote $path")
    }

    internal fun readGitSha(): String? = runCatching {
        val proc = ProcessBuilder("git", "rev-parse", "HEAD")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        if (proc.waitFor() == 0 && out.isNotEmpty()) out else null
    }.getOrNull()

    internal fun formatNs(ns: Long): String = when {
        ns < 1_000 -> "${ns}ns"
        ns < 1_000_000 -> "${ns / 1_000}µs"
        ns < 1_000_000_000 -> "${ns / 1_000_000}ms"
        else -> "${ns / 1_000_000_000}s"
    }
}

/** Environment metadata captured in every bench output JSON so results stay interpretable. */
@Serializable
internal data class EnvInfo(
    val javaVersion: String,
    val javaVendor: String,
    val osName: String,
    val osArch: String,
    val cpuCount: Int,
) {
    companion object {
        fun capture(): EnvInfo = EnvInfo(
            javaVersion = System.getProperty("java.version") ?: "unknown",
            javaVendor = System.getProperty("java.vendor") ?: "unknown",
            osName = System.getProperty("os.name") ?: "unknown",
            osArch = System.getProperty("os.arch") ?: "unknown",
            cpuCount = Runtime.getRuntime().availableProcessors(),
        )
    }
}

// --- Time metric ---

@Serializable
internal data class CellResult(
    val backend: String,
    val solveNsMedian: Long,
    val sampleNsMedian: Long,
    val enumNsMedian: Long,
)

@Serializable
internal data class EntryResult(val name: String, val expectedSat: Boolean, val backends: List<CellResult>)

@Serializable
internal data class BenchResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val repetitions: Int,
    val sampleCount: Int,
    val warmupReps: Int,
    val entries: List<EntryResult>,
)
