package com.eignex.klause.bench

import kotlinx.serialization.Serializable

@Serializable
data class CellResult(
    val backend: String,
    val solveNsMedian: Long,
    val sampleNsMedian: Long,
    val enumNsMedian: Long,
)

@Serializable
data class EntryResult(
    val name: String,
    val expectedSat: Boolean,
    val backends: List<CellResult>,
)

/**
 * Environment metadata captured in every bench output JSON so results stay interpretable
 * when shared across machines / CI / collaborators.
 */
@Serializable
data class EnvInfo(
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

@Serializable
data class BenchResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val repetitions: Int,
    val sampleCount: Int,
    val warmupReps: Int,
    val entries: List<EntryResult>,
)
