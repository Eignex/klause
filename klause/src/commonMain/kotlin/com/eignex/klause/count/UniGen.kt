package com.eignex.klause.count

import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

// Near-uniform XOR-hashed sampling (UniGen2), an accuracy-validation tool for measuring how biased the cheap
// sampling path is: S. Chakraborty, D. J. Fremont, K. S. Meel, S. A. Seshia, M. Y. Vardi, "On Parallel Scalable
// Uniform SAT Witness Generation", TACAS 2015, https://doi.org/10.1007/978-3-662-46681-0_25
//
// Estimates the projected count C once with ApproxMC. A small enough C is enumerated and sampled exactly. Otherwise
// it draws `m ≈ log2(C / pivot)` hashes and picks uniformly from a cell whose size lands in [loThresh, hiThresh],
// retrying out-of-band cells with a fresh family and falling back to cheapFallback after MAX_CONSECUTIVE_REJECTS.
internal object UniGen {

    fun samples(
        problem: BakedProblem,
        config: SamplingConfig,
        cheapFallback: () -> Sequence<Sample>,
    ): Sequence<Sample> = sequence {
        val ctx = CellContext.resolve(problem, config.samplingSet, config.intSamplingSet)
        val kappa = config.tolerance
        val pivot = ceil(4.03 * (1.0 + 1.0 / kappa) * (1.0 + 1.0 / kappa)).toInt()
        val loThresh = floor(pivot / (1.0 + kappa)).toInt().coerceAtLeast(1)
        val hiThresh = ceil(1.0 + (1.0 + kappa) * pivot).toInt()

        val estimate = ApproxMC.run(
            problem,
            ApproxCountConfig(
                epsilon = config.countEpsilon,
                delta = config.countDelta,
                samplingSet = config.samplingSet,
                intSamplingSet = config.intSamplingSet,
                seed = config.seed,
            ),
        )
        val count = estimate.estimate
        if (count == 0L && estimate.exact) return@sequence
        if (estimate.confidence == 0.0) {
            yieldAll(cheapFallback())
            return@sequence
        }

        var seedCounter = config.seed ?: Random.Default.nextLong()

        // Gate on the bounded enumeration, not the lossy estimate: a capped set is a search-order-biased
        // truncation, so it falls through to hashing.
        if (count <= hiThresh) {
            val all = cellCount(ctx, hashes = emptyList(), cap = hiThresh)
            if (all.complete) {
                if (all.representatives.isEmpty()) return@sequence
                while (true) {
                    val rng = Random(seedCounter++)
                    yield(all.representatives[rng.nextInt(all.representatives.size)])
                }
            }
        }

        // Otherwise hash down to a cell in the target band, then pick uniformly within it.
        val mStar = (log2(count.toDouble()) - log2(pivot.toDouble())).roundToInt().coerceAtLeast(0)
        val sampler = CellSampler(ctx, mStar, loThresh, hiThresh)
        var consecutiveRejects = 0
        while (true) {
            val s = sampler.draw(seedCounter++)
            if (s != null) {
                consecutiveRejects = 0
                yield(s)
            } else if (++consecutiveRejects >= MAX_CONSECUTIVE_REJECTS) {
                // Hashing can't find an in-band cell (highly skewed count or hard slices).
                yieldAll(cheapFallback())
                return@sequence
            }
        }
    }

    private const val MAX_CONSECUTIVE_REJECTS = 64

    private class CellSampler(
        private val ctx: CellContext,
        private val mStar: Int,
        private val loThresh: Int,
        private val hiThresh: Int,
    ) {
        private var unhashedOverCap = false

        fun draw(seed: Long): Sample? {
            val n = ctx.hashDomain.size
            val allHashes = XorHashFamily(ctx.hashDomain, seed).draw(n)
            val rng = Random(seed * 0x9E3779B97F4A7C15uL.toLong() + 1)
            val lo = (mStar - 1).coerceAtLeast(0)
            val hi = (mStar + 1).coerceAtMost(n)
            for (m in lo..hi) {
                if (m == 0 && unhashedOverCap) continue
                val cell = cellCount(ctx, allHashes.subList(0, m), cap = hiThresh)
                // Every hash family has the same zero-hash cell in this conditioned projection.
                if (m == 0 && cell.capped) unhashedOverCap = true
                if (cell.complete && cell.count in loThresh..hiThresh) {
                    return cell.representatives[rng.nextInt(cell.representatives.size)]
                }
            }
            return null
        }
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)
}
