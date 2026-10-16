package com.eignex.klause.count

import com.eignex.klause.factor.bool.GaussianXor
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.util.IntArrayList
import kotlin.random.Random

/**
 * A family of random XOR (parity) hash constraints over a [samplingSet] of Boolean variables.
 *
 * Drawing `m` independent hashes partitions the satisfying assignments (projected onto the
 * sampling set) into roughly `2^m` cells of near-equal expected size — the core primitive
 * shared by ApproxMC counting ([ApproxMC]) and UniGen2 sampling ([UniGen]).
 *
 * Each hash is `XOR(subset) == parityBit` where every var in [samplingSet] is included with
 * probability ½ and `parityBit` is a fair coin: the 3-wise independent `H_xor` family of
 * S. Chakraborty, K. S. Meel, M. Y. Vardi, "A Scalable Approximate Model Counter", CP 2013,
 * https://doi.org/10.1007/978-3-642-40627-0_18. Hashes are [Xor] values that `withHashes` bundles
 * into one [GaussianXor] system.
 *
 * Construction is reproducible: the same [seed] (and the same `m` and draw order) yields the
 * same hashes.
 */
class XorHashFamily(
    /** Boolean variable ids the hashes range over. */
    val samplingSet: IntArray,
    /** Seed for the hash-selection RNG. */
    seed: Long,
) {
    private val rng = Random(seed)

    /**
     * Draw [m] independent parity hashes over [samplingSet]. Returns an empty list when
     * `m == 0` (the trivial single-cell partition). Each hash includes at least one variable:
     * an all-excluded draw would degenerate to a constant `0 == parityBit` constraint, so one
     * uniformly chosen sampling variable is force-included instead. This slightly over-weights
     * singleton subsets relative to the ideal family.
     */
    fun draw(m: Int): List<Xor> {
        require(m >= 0) { "m must be non-negative, was $m" }
        if (m == 0 || samplingSet.isEmpty()) return emptyList()
        val hashes = ArrayList<Xor>(m)
        repeat(m) { hashes.add(drawOne()) }
        return hashes
    }

    private fun drawOne(): Xor {
        val chosen = IntArrayList(samplingSet.size)
        for (v in samplingSet) {
            if (rng.nextBoolean()) chosen.add(Lit.make(v, positive = true))
        }
        if (chosen.isEmpty()) {
            val v = samplingSet[rng.nextInt(samplingSet.size)]
            chosen.add(Lit.make(v, positive = true))
        }
        val parity = if (rng.nextBoolean()) 1 else 0
        return Xor(chosen.toIntArray(), parity)
    }
}

/**
 * The `Problem` augmented with [hashes] as a single [GaussianXor] system. Variable counts and
 * domains are unchanged; only one constraint is appended. The parity constraints are propagated
 * jointly by Gauss-Jordan elimination, which is what makes enumerating a hashed cell tractable
 * (a per-hash [Xor] factor cannot — see [GaussianXor]).
 */
internal fun BakedProblem.withHashes(hashes: List<Xor>): Problem {
    if (hashes.isEmpty()) return this
    return withFactors(
        newFactors = factors + GaussianXor(hashes),
        newImpliedFactorMask = impliedFactorMask?.let { it + false },
    )
}
