package com.eignex.klause.solver.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** The float revised simplex must find an optimal basis whose exact certification ([ExactBasisCertifier])
 *  reproduces the float optimum — the float-iterate / exact-certify-once contract (#567 / #705). */
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
        repeat(500) {
            val model = randomModel(rng.nextInt(3, 9), rng.nextInt(3, 9), rng)
            val rev = RevisedSimplex(model).solve() ?: return@repeat
            val cert = ExactBasisCertifier.certify(model, rev.basis) ?: return@repeat
            converged++
            val exact = cert.objective.num.toDouble() / cert.objective.den.toDouble()
            assertTrue(
                abs(rev.objective + 0.0 - exact) <= 1e-6 * maxOf(1.0, abs(exact)),
                "float obj ${rev.objective} vs exact certify $exact",
            )
        }
        assertTrue(converged > 200, "revised converged on only $converged instances")
    }
}
