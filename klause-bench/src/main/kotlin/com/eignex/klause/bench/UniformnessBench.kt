package com.eignex.klause.bench

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.brute.BruteForceParams
import com.eignex.klause.solver.brute.BruteForceSolver
import kotlin.math.ln
import kotlinx.serialization.Serializable

/**
 * Per-backend sampling-quality metrics for one [Problem]. Oracle fields ([coverageFraction],
 * [klFromUniform]) are populated only when the feasible space is enumerable via
 * [BruteForceSolver]; otherwise they're null and the no-oracle metrics
 * ([distinctnessRatio], [meanPairwiseHamming], [sampleEntropy]) carry the analysis alone.
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
    /** Shannon entropy over the empirical distribution of yielded samples (nats). Higher =
     *  more spread across observed models. Always ≤ ln(distinctCount). */
    val sampleEntropy: Double,
    /** Fraction of feasible models reached. Null when no oracle. */
    val coverageFraction: Double?,
    /** KL divergence (nats) from uniform over feasible models. Null when no oracle. */
    val klFromUniform: Double?,
    /** Number of feasible models, when known. */
    val oracleFeasibleCount: Int?,
)

object UniformnessBench {

    /** Default cap on oracle invocations — brute-force can balloon on bigger problems. */
    private const val ORACLE_MAX_MODELS = 4096

    fun analyse(
        entryName: String,
        backend: BenchSolver,
        sampleCount: Int = 200,
    ): UniformnessReport {
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
            for (j in (i + 1) until distinctList.size) {
                hammings += hamming(distinctList[i], distinctList[j])
            }
        }
        val meanH = if (hammings.isEmpty()) 0.0 else hammings.average()
        val p5 = percentile(hammings, 5.0)
        val p95 = percentile(hammings, 95.0)

        // Oracle (if enumerable + small enough).
        val (coverage, kl, oracleSize) = oracleStats(backend.problem, counts, n)

        return UniformnessReport(
            entryName = entryName,
            backendName = backend.name,
            sampleCount = n,
            distinctCount = distinct.size,
            distinctnessRatio = if (n == 0) 0.0 else distinct.size.toDouble() / n,
            meanPairwiseHamming = meanH,
            pairwiseHammingP5 = p5,
            pairwiseHammingP95 = p95,
            sampleEntropy = entropy,
            coverageFraction = coverage,
            klFromUniform = kl,
            oracleFeasibleCount = oracleSize,
        )
    }

    private fun oracleStats(
        problem: Problem,
        counts: Map<Sample, Int>,
        n: Int,
    ): Triple<Double?, Double?, Int?> {
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
        // KL(P̂ || U) over feasible models. U(x) = 1/total. P̂(x) = count(x)/n if seen, 0 otherwise.
        // For seen x: P̂(x) log(P̂(x) * total). Unseen contribute 0 (with limit convention).
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
