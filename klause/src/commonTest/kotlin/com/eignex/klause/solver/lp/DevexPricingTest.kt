package com.eignex.klause.solver.lp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #18: Dantzig (largest-infeasibility) leaving-variable pricing with a Bland anti-cycling fallback.
 * Correctness of the pricing change is covered by the randomized Double-oracle parity in
 * [DualSimplexTest]; these tests focus on termination under degeneracy, where naive Dantzig can
 * cycle and the fallback must engage.
 */
class DevexPricingTest {

    @Test
    fun `highly degenerate covering LP solves to the correct optimum`() {
        // 6 unit-bounded vars, all tied (x_i = x_0) and required to sum to >= 3. Every basis is
        // degenerate; the solver must still reach the optimum (each x_i = 0.5, sum = 3).
        val b = LpBuilder()
        val xs = IntArray(6) { b.addVar(0, 1, cost = 1) }
        for (i in 1 until 6) {
            b.addRow(mapOf(xs[0] to 1L, xs[i] to -1L), Relation.EQ, 0) // x_i = x_0
        }
        b.addRow((0 until 6).associate { xs[it] to 1L }, Relation.GE, 3)
        val sol = DualSimplex(b.build(Sense.MINIMIZE)).solve()

        assertEquals(LpStatus.OPTIMAL, sol.status)
        assertEquals(3.0, sol.objectiveValue, 1e-9)
    }

    @Test
    fun `randomized degenerate instances always terminate`() {
        // Tight, redundant constraints make most bases degenerate. The cycle-guard `check` in the
        // engine throws if pricing fails to terminate, so reaching a status at all is the assertion.
        val rng = Random(20260609)
        repeat(300) {
            val n = rng.nextInt(2, 5)
            val b = LpBuilder()
            val xs = IntArray(n) { b.addVar(0, rng.nextInt(1, 4).toLong(), cost = rng.nextInt(-3, 4).toLong()) }
            repeat(rng.nextInt(2, 6)) {
                val coeffs = HashMap<Int, Long>()
                for (k in 0 until n) if (rng.nextBoolean()) coeffs[xs[k]] = rng.nextInt(-2, 3).toLong()
                if (coeffs.isEmpty()) return@repeat
                val rel = when (rng.nextInt(3)) {
                    0 -> Relation.LE
                    1 -> Relation.GE
                    else -> Relation.EQ
                }
                b.addRow(coeffs, rel, rng.nextInt(-2, 6).toLong())
            }
            val sol = DualSimplex(b.build(Sense.MINIMIZE)).solve()
            assertTrue(sol.status == LpStatus.OPTIMAL || sol.status == LpStatus.INFEASIBLE)
        }
    }
}
