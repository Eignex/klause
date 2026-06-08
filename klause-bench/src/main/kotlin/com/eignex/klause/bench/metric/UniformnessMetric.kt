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
import com.eignex.kumulant.stat.cardinality.HyperLogLogStat
import com.eignex.kumulant.stat.quantile.TDigestStat
import com.eignex.kumulant.stat.summary.MeanStat
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.Locale
import kotlin.math.ln

/**
 * Per-backend sampling-quality metrics for one [Problem]. Oracle fields ([coverageFraction],
 * [klFromUniform]) are populated only when the feasible space is enumerable via
 * [BruteForceSolver]; otherwise the no-oracle metrics carry the analysis alone.
 */
@Serializable
data class UniformnessReport(
    internal val entryName: String,
    internal val backendName: String,
    internal val sampleCount: Int,
    internal val distinctCount: Int,
    internal val distinctnessRatio: Double,
    /** HyperLogLog estimate of [distinctCount] from streamed sample hashes — the scale-out
     *  path for sample counts where exact distinct-tracking stops being feasible; reported
     *  alongside the exact count so the estimator stays validated at exact-trackable sizes. */
    val distinctEstimate: Double? = null,
    internal val meanPairwiseHamming: Double,
    internal val pairwiseHammingP5: Double,
    internal val pairwiseHammingP95: Double,
    internal val sampleEntropy: Double,
    val coverageFraction: Double?,
    val klFromUniform: Double?,
    internal val oracleFeasibleCount: Int?,
)

@Serializable
internal data class UniformnessResults(
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
    private const val P_LO = 0.05
    private const val P_HI = 0.95

    internal fun run(entries: List<ResolvedProblem>) {
        val sampleCount = System.getProperty("klause.bench.uniformness.samples")?.toIntOrNull() ?: DEFAULT_SAMPLE_COUNT
        println()
        println(
            "=== uniformness bench (n=$sampleCount per backend; entropy & Hamming always reported, " +
                "coverage + KL only when oracle fits) ===",
        )
        val results = mutableListOf<UniformnessReport>()
        for (entry in entries) {
            val rows = mutableListOf<String>()
            for (backend in Solvers.defaultPortfolio(entry.problem)) {
                val r = analyse(entry.name, backend, sampleCount)
                results += r
                val cov = r.coverageFraction?.let { "%.2f".format(Locale.ROOT, it) } ?: "—"
                val kl = r.klFromUniform?.let { "%.3f".format(Locale.ROOT, it) } ?: "—"
                rows += "${backend.name} distinct=${r.distinctCount}/${r.sampleCount} " +
                    "Hp50=${"%.1f".format(Locale.ROOT, r.meanPairwiseHamming)} " +
                    "H=${"%.2f".format(Locale.ROOT, r.sampleEntropy)} cov=$cov KL=$kl"
            }
            println("[${entry.name}] ${rows.joinToString(" | ")}")
        }
        Reports.writeJson(
            "build/bench-uniformness.json",
            UniformnessResults(Instant.now().toString(), Reports.readGitSha(), EnvInfo.capture(), sampleCount, results),
        )
    }

    internal fun analyse(
        entryName: String,
        backend: InProcessSolver,
        sampleCount: Int = DEFAULT_SAMPLE_COUNT,
    ): UniformnessReport {
        val samples = backend.samplesSequence().take(sampleCount).toList()
        val n = samples.size
        val counts: Map<Sample, Int> = samples.groupingBy { it }.eachCount()
        val hll = HyperLogLogStat()
        samples.forEach { hll.update(it.hashCode().toLong()) }
        val entropy = counts.values.sumOf { c ->
            val p = c.toDouble() / n
            if (p <= 0.0) 0.0 else -p * ln(p)
        }
        // Pairwise Hamming spread, streamed into a t-digest + mean: O(1) memory where the old
        // hand-rolled percentile materialised every pair, and proper interpolated quantiles
        // instead of a floor-indexed nearest rank.
        val digest = TDigestStat(probabilities = doubleArrayOf(P_LO, P_HI))
        val meanStat = MeanStat()
        var pairs = 0L
        val distinctList = counts.keys.toList()
        for (i in distinctList.indices) {
            for (j in (i + 1) until distinctList.size) {
                val d = distinctList[i].hammingDistanceTo(distinctList[j]).toDouble()
                digest.update(d)
                meanStat.update(d)
                pairs++
            }
        }
        val quantiles = digest.read().quantiles
        val (coverage, kl, oracleSize) = oracleStats(backend.problem, counts, n)

        return UniformnessReport(
            entryName = entryName,
            backendName = backend.name,
            sampleCount = n,
            distinctCount = distinctList.size,
            distinctnessRatio = if (n == 0) 0.0 else distinctList.size.toDouble() / n,
            distinctEstimate = if (n == 0) null else hll.read().estimate,
            meanPairwiseHamming = if (pairs == 0L) 0.0 else meanStat.read().mean,
            pairwiseHammingP5 = if (pairs == 0L) 0.0 else quantiles[0],
            pairwiseHammingP95 = if (pairs == 0L) 0.0 else quantiles[1],
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
}
