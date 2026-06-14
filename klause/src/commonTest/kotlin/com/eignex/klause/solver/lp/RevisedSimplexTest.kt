package com.eignex.klause.solver.lp

import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The float revised simplex must find an optimal basis: certifying it with the exact [DualSimplex]
 *  reproduces the cold-exact optimum (#567 component 2). */
class RevisedSimplexTest {

    /** Random feasible bounded LP: x in [0, ub], `Σ a·x ≤ rhs` with rhs ≥ 0 (x = 0 feasible),
     *  small coefficients so the exact oracle does not overflow. */
    private fun randomModel(m: Int, n: Int, rng: Random): LpModel {
        val b = LpBuilder()
        for (j in 0 until n) b.addVar(0L, rng.nextLong(2, 8), cost = rng.nextLong(-6, 7))
        repeat(m) {
            val cols = IntArray(n) { it }
            val vals = LongArray(n) { rng.nextLong(-3, 4) }
            b.addRow(cols, vals, Relation.LE, rng.nextLong(3, 20))
        }
        return b.build(Sense.MINIMIZE)
    }

    @Test
    fun `revised basis certifies to the cold-exact optimum`() {
        val rng = Random(20260614)
        var checked = 0
        var converged = 0
        repeat(500) {
            val model = randomModel(rng.nextInt(3, 9), rng.nextInt(3, 9), rng)
            val exact = try {
                DualSimplex(model).solve()
            } catch (_: LpOverflowException) {
                return@repeat // exact oracle overflowed on this model — skip
            }
            if (exact.status != LpStatus.OPTIMAL) return@repeat
            checked++

            val rev = RevisedSimplex(model).solve() ?: return@repeat
            converged++
            // Float objective agrees with the exact optimum (relative tolerance).
            val scale = maxOf(1.0, abs(exact.objectiveValue))
            assertTrue(
                abs(rev.objective - exact.objectiveValue) <= 1e-6 * scale,
                "float obj ${rev.objective} vs exact ${exact.objectiveValue}",
            )
            // Certifying the revised basis with the exact solver reaches the same optimum.
            val certified = DualSimplex(model).solve(rev.basis)
            assertEquals(LpStatus.OPTIMAL, certified.status, "certified status")
            assertEquals(
                exact.objectiveValue,
                certified.objectiveValue,
                1e-9,
                "certified optimum from the revised basis",
            )
        }
        assertTrue(checked > 200, "covered only $checked feasible instances")
        assertTrue(converged >= checked * 9 / 10, "revised converged on only $converged/$checked")
    }
}
