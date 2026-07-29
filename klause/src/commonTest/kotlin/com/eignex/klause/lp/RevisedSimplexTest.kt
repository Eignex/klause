package com.eignex.klause.lp

import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** The float revised simplex must find an optimal basis whose integer-multiplier certification
 *  ([integerDualLowerBoundCeil]) reproduces `ceil` of the float optimum — the float-iterate /
 *  certify-once contract (#567 / #705). */
class RevisedSimplexTest {

    private fun randomModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        repeat(n) { b.addVar(0L, rng.nextLong(2, 8), cost = rng.nextLong(-6, 7)) }
        val cols = IntArray(n) { it }
        repeat(m) { b.addRow(cols, LongArray(n) { rng.nextLong(-3, 4) }, Relation.LE, rng.nextLong(3, 20)) }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `revised basis certifies to the float optimum`() {
        val rng = Random(20260614)
        var converged = 0
        var tight = 0
        repeat(500) {
            val model = randomModel(rng.nextInt(3, 9), rng.nextInt(3, 9), rng)
            val rev = RevisedSimplex(model).solve() ?: return@repeat
            val bound = integerDualLowerBoundCeil(model, rev.duals) ?: return@repeat
            converged++
            val ceilOpt = ceil(rev.objective)
            // Sound: the certified integer bound never exceeds ceil(LP optimum); tight on the majority.
            assertTrue(bound.toDouble() <= ceilOpt + 1e-6, "bound $bound > ceil(obj ${rev.objective})")
            if (bound.toDouble() in (ceilOpt - 0.5)..(ceilOpt + 0.5)) tight++
        }
        assertTrue(converged > 200, "revised converged on only $converged instances")
        assertTrue(tight >= converged * 2 / 3, "certified bound was tight on only $tight/$converged")
    }

    @Test
    fun `gated resolve matches a fresh solve of the enforced submodel`() {
        val rng = Random(20260729)
        var checked = 0
        repeat(40) {
            val n = rng.nextInt(3, 7)
            val m = rng.nextInt(4, 10)
            val uppers = LongArray(n) { rng.nextLong(2, 8) }
            val rowCoeffs = Array(m) { LongArray(n) { rng.nextLong(-3, 4) } }
            val rowRhs = LongArray(m) { rng.nextLong(-6, 12) }
            val persistent = RevisedSimplex(zeroCostModel(uppers, rowCoeffs, rowRhs, BooleanArray(m) { true }))
            val enforced = BooleanArray(m)
            repeat(25) {
                for (i in 0 until m) enforced[i] = rng.nextBoolean()
                val gated = persistent.resolveGated(enforced) != null
                val kept = enforced.copyOf()
                val fresh = RevisedSimplex(zeroCostModel(uppers, rowCoeffs, rowRhs, kept)).solve() != null
                checked++
                assertTrue(gated == fresh, "gated=$gated fresh=$fresh for enforcement ${kept.toList()}")
            }
        }
        assertTrue(checked == 1000, "expected 1000 enforcement checks, ran $checked")
    }

    /** A zero-objective model over `x_j ∈ [0, uppers(j)]` with only the rows where `keep(i)`;
     *  with `keep` all-true this is the gated filter's full model, else the enforced submodel. */
    private fun zeroCostModel(
        uppers: LongArray,
        rowCoeffs: Array<LongArray>,
        rowRhs: LongArray,
        keep: BooleanArray,
    ): LpModel {
        val b = LpBuilder()
        for (u in uppers) b.addVar(0L, u)
        val cols = IntArray(uppers.size) { it }
        for (i in rowCoeffs.indices) {
            if (keep[i]) b.addRow(cols, rowCoeffs[i], Relation.LE, rowRhs[i])
        }
        return b.build(Sense.MINIMIZE)
    }
}
