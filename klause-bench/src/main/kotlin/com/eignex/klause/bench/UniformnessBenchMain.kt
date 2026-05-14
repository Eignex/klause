package com.eignex.klause.bench

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class UniformnessResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val sampleCount: Int,
    val entries: List<UniformnessReport>,
)

/**
 * Entry point: `./gradlew :klause-bench:runUniformness`. Draws [SAMPLE_COUNT] independent
 * samples per backend per portfolio entry and measures distinctness / Hamming spread /
 * entropy. Adds coverage + KL-from-uniform when the feasible space is enumerable.
 */
fun main() {
    val sampleCount = System.getProperty("klause.bench.uniformness.samples")?.toIntOrNull() ?: SAMPLE_COUNT
    val (_, benchEntries, _) = BenchHarness.loadAndVerify()
    println()
    println("=== uniformness bench (n=$sampleCount per backend; entropy & Hamming always reported, " +
        "coverage + KL only when oracle fits) ===")
    val results = mutableListOf<UniformnessReport>()
    for (entry in benchEntries) {
        val rows = mutableListOf<String>()
        for (backend in defaultSolvers(entry.problem)) {
            val r = UniformnessBench.analyse(entry.name, backend, sampleCount)
            results += r
            val cov = r.coverageFraction?.let { "%.2f".format(it) } ?: "—"
            val kl = r.klFromUniform?.let { "%.3f".format(it) } ?: "—"
            rows += "${backend.name} distinct=${r.distinctCount}/${r.sampleCount} " +
                "Hp50=${"%.1f".format(r.meanPairwiseHamming)} " +
                "H=${"%.2f".format(r.sampleEntropy)} cov=$cov KL=$kl"
        }
        println("[${entry.name}] ${rows.joinToString(" | ")}")
    }
    BenchHarness.writeJson(
        "build/bench-uniformness.json",
        UniformnessResults(Instant.now().toString(), BenchHarness.readGitSha(), EnvInfo.capture(), sampleCount, results),
    )
}

private const val SAMPLE_COUNT: Int = 200
