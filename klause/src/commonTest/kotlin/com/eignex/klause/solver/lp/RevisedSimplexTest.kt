package com.eignex.klause.solver.lp

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
}
