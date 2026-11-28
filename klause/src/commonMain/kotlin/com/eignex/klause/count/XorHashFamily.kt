package com.eignex.klause.count

import com.eignex.klause.factor.bool.GaussianXor
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.BakedProblem
import com.eignex.util.collections.IntArrayList
import kotlin.random.Random

/**
 * A family of random XOR (parity) hash constraints over a [samplingSet] of Boolean variables. Drawing `m` hashes
 * partitions the projected satisfying assignments into roughly `2^m` cells of near-equal expected size.
 *
 * Each hash is `XOR(subset) == parityBit`, every variable included with probability ½ and `parityBit` a fair coin:
 * the 3-wise independent `H_xor` family of S. Chakraborty, K. S. Meel, M. Y. Vardi, "A Scalable Approximate Model
 * Counter", CP 2013, https://doi.org/10.1007/978-3-642-40627-0_18. The same [seed], `m` and draw order yield the
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
     * Draw [m] independent parity hashes over [samplingSet]; empty when `m == 0`. An all-excluded draw would be a
     * constant constraint, so one uniformly chosen variable is force-included instead, slightly over-weighting
     * singleton subsets.
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

// The hashes form one GaussianXor system: Gauss-Jordan propagation is what makes enumerating a hashed cell
// tractable, which per-hash Xor factors are not.
internal fun BakedProblem.withHashes(hashes: List<Xor>): Problem {
    if (hashes.isEmpty()) return this
    return withFactors(
        newFactors = factors + GaussianXor(hashes),
        newImpliedFactorMask = impliedFactorMask?.let { it + false },
    )
}
