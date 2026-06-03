package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.EnvInfo
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.bench.solver.InProcessSolver
import com.eignex.klause.bench.solver.Solvers
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.brute.BruteForceParams
import com.eignex.klause.solver.brute.BruteForceSolver
import java.time.Instant
import kotlin.math.ln
import kotlinx.serialization.Serializable

/**
 * Per-backend sampling-quality metrics for one [Problem]. Oracle fields ([coverageFraction],
 * [klFromUniform]) are populated only when the feasible space is enumerable via
 * [BruteForceSolver]; otherwise the no-oracle metrics carry the analysis alone.
 */
@Serializable
data class UniformnessReport(
    val entryName: String,
    val backendName: String,
    val sampleCount: Int,
    val distinctCount: Int,
    val distinctnessRatio: Double,
    val meanPairwiseHamming: Double,
    val pairwiseHammingP5: Double,
    val pairwiseHammingP95: Double,
    val sampleEntropy: Double,
    val coverageFraction: Double?,
    val klFromUniform: Double?,
    val oracleFeasibleCount: Int?,
)

@Serializable
data class UniformnessResults(
    val timestamp: String,
    val gitSha: String?,
    val env: EnvInfo,
    val sampleCount: Int,
    val entries: List<UniformnessReport>,
)

/** Draws independent samples per backend and measures distinctness / Hamming spread /
 *  entropy, adding coverage + KL-from-uniform when the feasible space is enumerable. */
object UniformnessMetric {
    private const val ORACLE_MAX_MODELS = 4096
    private const val DEFAULT_SAMPLE_COUNT = 200

    fun run(entries: List<ResolvedProblem>) {
        val sampleCount = System.getProperty("klause.bench.uniformness.samples")?.toIntOrNull() ?: DEFAULT_SAMPLE_COUNT
        println()
        println("=== uniformness bench (n=$sampleCount per backend; entropy & Hamming always reported, " +
            "coverage + KL only when oracle fits) ===")
        val results = mutableListOf<UniformnessReport>()
        for (entry in entries) {
            val rows = mutableListOf<String>()
            for (backend in Solvers.defaultPortfolio(entry.problem)) {
                val r = analyse(entry.name, backend, sampleCount)
                results += r
                val cov = r.coverageFraction?.let { "%.2f".format(it) } ?: "—"
                val kl = r.klFromUniform?.let { "%.3f".format(it) } ?: "—"
                rows += "${backend.name} distinct=${r.distinctCount}/${r.sampleCount} " +
                    "Hp50=${"%.1f".format(r.meanPairwiseHamming)} " +
                    "H=${"%.2f".format(r.sampleEntropy)} cov=$cov KL=$kl"
            }
            println("[${entry.name}] ${rows.joinToString(" | ")}")
        }
        Reports.writeJson(
            "build/bench-uniformness.json",
            UniformnessResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), sampleCount, results),
        )
    }

    fun analyse(entryName: String, backend: InProcessSolver, sampleCount: Int = DEFAULT_SAMPLE_COUNT): UniformnessReport {
        val samples = backend.samplesSequence().take(sampleCount).toList()
        val n = samples.size
        val distinct = samples.toSet()
        val counts: Map<Sample, Int> = samples.groupingBy { it }.eachCount()
        val entropy = counts.values.sumOf { c ->
            val p = c.toDouble() / n
            if (p <= 0.0) 0.0 else -p * ln(p)
        }
        val hammings = ArrayList<Int>(distinct.size * (distinct.size - 1) / 2 + 1)
        val distinctList = distinct.toList()
        for (i in distinctList.indices) {
            for (j in (i + 1) until distinctList.size) hammings += hamming(distinctList[i], distinctList[j])
        }
        val meanH = if (hammings.isEmpty()) 0.0 else hammings.average()
        val (coverage, kl, oracleSize) = oracleStats(backend.problem, counts, n)

        return UniformnessReport(
            entryName = entryName,
            backendName = backend.name,
            sampleCount = n,
            distinctCount = distinct.size,
            distinctnessRatio = if (n == 0) 0.0 else distinct.size.toDouble() / n,
            meanPairwiseHamming = meanH,
            pairwiseHammingP5 = percentile(hammings, 5.0),
            pairwiseHammingP95 = percentile(hammings, 95.0),
            sampleEntropy = entropy,
            coverageFraction = coverage,
            klFromUniform = kl,
            oracleFeasibleCount = oracleSize,
        )
    }

    private fun oracleStats(problem: Problem, counts: Map<Sample, Int>, n: Int): Triple<Double?, Double?, Int?> {
        if (!BruteForceSolver.fits(problem)) return Triple(null, null, null)
        val oracle: Set<Sample> = BruteForceSolver(problem)
            .enumerate(BruteForceParams())
            .take(ORACLE_MAX_MODELS + 1)
            .toSet()
        if (oracle.size > ORACLE_MAX_MODELS) return Triple(null, null, null)
        val total = oracle.size
        if (total == 0) return Triple(null, null, 0)
        val seenInOracle = counts.keys.count { it in oracle }
        val coverage = seenInOracle.toDouble() / total
        val kl = counts.entries.sumOf { (s, c) ->
            if (s !in oracle) return@sumOf 0.0
            val p = c.toDouble() / n
            p * ln(p * total)
        }
        return Triple(coverage, kl, total)
    }

    private fun hamming(a: Sample, b: Sample): Int {
        var d = 0
        for (i in a.bools.indices) if (a.bools[i] != b.bools[i]) d++
        for (i in a.ints.indices) if (a.ints[i] != b.ints[i]) d++
        return d
    }

    private fun percentile(values: List<Int>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val idx = ((p / 100.0) * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx].toDouble()
    }
}
