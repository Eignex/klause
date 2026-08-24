package com.eignex.klause.count

import com.eignex.klause.factor.bool.GaussianXor
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
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
 * probability ½ and `parityBit` is a fair coin. This is the standard random 3-independent-ish
 * affine hash family from Chakraborty, Meel & Vardi. Hashes are realised as native [Xor]
 * factors, so the [com.eignex.klause.backtrack.BacktrackSolver] propagates them directly.
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
     * sampling variable is force-included to keep the hash non-trivial (this preserves the
     * uniform-hash distribution over the non-empty subsets actually used by the algorithms).
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
            // Avoid the degenerate constant constraint: include one uniformly-random var.
            val v = samplingSet[rng.nextInt(samplingSet.size)]
            chosen.add(Lit.make(v, positive = true))
        }
        val parity = if (rng.nextBoolean()) 1 else 0
        return Xor(chosen.toIntArray(), parity)
    }
}

/**
 * The [Problem] augmented with [hashes] as a single [GaussianXor] system. Variable counts and
 * domains are unchanged; only one constraint is appended. The parity constraints are propagated
 * jointly by Gauss-Jordan elimination, which is what makes enumerating a hashed cell tractable
 * (a per-hash [Xor] factor cannot — see [GaussianXor]).
 */
internal fun Problem.withHashes(hashes: List<Xor>): Problem {
    if (hashes.isEmpty()) return this
    val merged = ArrayList<Factor>(factors.size + 1)
    merged.addAll(factors)
    merged.add(GaussianXor(hashes))
    return Problem(
        numBoolVars = numBoolVars,
        numIntVars = numIntVars,
        intDomains = requireFiniteIntDomains(),
        factors = merged,
    )
}
