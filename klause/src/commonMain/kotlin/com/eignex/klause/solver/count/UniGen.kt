package com.eignex.klause.solver.count

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Near-uniform XOR-hashed sampling (UniGen2; Chakraborty, Meel & Vardi) over a Boolean sampling
 * set. An *accuracy-validation* tool: it produces (almost) uniform draws to measure how biased the
 * cheap sampling path is. Far more expensive than the cheap path.
 *
 * XOR hashes range over the projection's bits (see [cellCount]). Strategy: estimate the projected model
 * count `C` once (via [ApproxMC]); if `C` already fits a target cell band, enumerate all models and
 * pick uniformly (exactly uniform). Otherwise draw `m ≈ log2(C / pivot)` hashes, enumerate the
 * resulting cell, and — when its size lands in the `[loThresh, hiThresh]` band — pick a member
 * uniformly at random. Out-of-band cells are rejected and retried with a fresh hash family; after
 * [MAX_CONSECUTIVE_REJECTS] failures it falls back to `cheapFallback` rather than spin forever.
 */
internal object UniGen {

    fun samples(problem: Problem, config: SamplingConfig, cheapFallback: () -> Sequence<Sample>): Sequence<Sample> =
        sequence {
            val ctx = CellContext.resolve(problem, config.samplingSet, config.intSamplingSet)
            val kappa = config.tolerance
            val pivot = ceil(4.03 * (1.0 + 1.0 / kappa) * (1.0 + 1.0 / kappa)).toInt()
            val loThresh = floor(pivot / (1.0 + kappa)).toInt().coerceAtLeast(1)
            val hiThresh = ceil(1.0 + (1.0 + kappa) * pivot).toInt()

            // One-shot count estimate to pick the number of hashes.
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
            if (count == 0L) return@sequence // UNSAT projection: no samples

            var seedCounter = config.seed ?: Random.Default.nextLong()

            // Small enough to sample exactly-uniformly with no hashing — but gate on the real
            // bounded enumeration, not the lossy ε=0.8 estimate: a capped set is a search-order-biased
            // truncation, so fall through to hashing rather than sample it (#78).
            if (count <= hiThresh) {
                val all = cellCount(ctx, hashes = emptyList(), cap = hiThresh)
                if (!all.capped) {
                    if (all.representatives.isEmpty()) return@sequence
                    while (true) {
                        val rng = Random(seedCounter++)
                        yield(all.representatives[rng.nextInt(all.representatives.size)])
                    }
                }
            }

            // Otherwise hash down to a cell in the target band, then pick uniformly within it.
            val mStar = (log2(count.toDouble()) - log2(pivot.toDouble())).roundToInt().coerceAtLeast(0)
            var consecutiveRejects = 0
            while (true) {
                val s = drawOne(ctx, mStar, loThresh, hiThresh, seedCounter++)
                if (s != null) {
                    consecutiveRejects = 0
                    yield(s)
                } else if (++consecutiveRejects >= MAX_CONSECUTIVE_REJECTS) {
                    // Hashing can't find an in-band cell (highly skewed count or hard slices).
                    // Fall back to the cheap path rather than spin forever.
                    yieldAll(cheapFallback())
                }
            }
        }

    /** Give up on rejection sampling after this many consecutive out-of-band draws. */
    private const val MAX_CONSECUTIVE_REJECTS = 64

    private fun drawOne(ctx: CellContext, mStar: Int, loThresh: Int, hiThresh: Int, seed: Long): Sample? {
        val n = ctx.hashDomain.size
        val allHashes = XorHashFamily(ctx.hashDomain, seed).draw(n)
        val rng = Random(seed * 0x9E3779B97F4A7C15uL.toLong() + 1)
        // Search a small window of hash counts around mStar for a cell in the target band.
        val lo = (mStar - 1).coerceAtLeast(0)
        val hi = (mStar + 1).coerceAtMost(n)
        for (m in lo..hi) {
            val cell = cellCount(ctx, allHashes.subList(0, m), cap = hiThresh)
            if (!cell.capped && cell.count in loThresh..hiThresh) {
                return cell.representatives[rng.nextInt(cell.representatives.size)]
            }
        }
        return null // no in-band cell this round — reject; caller retries with a fresh seed
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)
}
