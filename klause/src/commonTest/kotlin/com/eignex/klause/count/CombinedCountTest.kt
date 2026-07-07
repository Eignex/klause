package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hybrid [com.eignex.klause.solver.Solver.count] over integer problems: anytime exact lb/ub
 * first ([AnytimeCounter], bit-blast-free), then the native channel-based ApproxMC ([ApproxMC])
 * clamped into the proven bounds. Both phases run without bit-blasting.
 */
class CombinedCountTest {

    private fun ints(count: Int, lo: Int, hi: Int, vararg factors: Factor) = Problem(
        numBoolVars = 0,
        numIntVars = count,
        intDomains = Array(count) { IntDomain(lo.toLong(), hi.toLong()) },
        factors = arrayOf(*factors),
    )

    @Test
    fun `exact phase converges on a small integer problem`() {
        val p = ints(3, 0, 3) // 64 combos, below the exact budget
        val r = BacktrackSolver(p).count(CountConfig(seed = 0L))
        assertTrue(r.exact, "small projection should be proved exactly by the anytime phase")
        assertEquals(64L, r.estimate)
        assertEquals(r.lower, r.upper)
    }

    @Test
    fun `exact phase converges on a constrained integer problem`() {
        // x0 + x1 <= 4 over 0..4: 15 feasible combos, proved exactly.
        val p = ints(2, 0, 4, Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 4))
        val r = BacktrackSolver(p).count(CountConfig(seed = 1L))
        assertTrue(r.exact)
        assertEquals(15L, r.estimate)
    }

    @Test
    fun `approx phase takes over when the exact budget is exhausted`() {
        // 5^3 = 125 combos, above the hashing threshold; a tiny exact budget forces the fallback.
        val p = ints(3, 0, 4)
        val r = BacktrackSolver(p).count(CountConfig(exactBudget = 4L, epsilon = 2.0, delta = 0.99, seed = 7L))
        assertTrue(!r.exact, "the exact phase cannot converge within 4 checks")
        // The hard lower bound from the partial exact phase still holds, and the clamped estimate
        // stays inside the merged interval.
        assertTrue(r.lower >= 1L, "at least the proven-feasible projections")
        assertTrue(r.estimate in r.lower..r.upper, "estimate ${r.estimate} outside [${r.lower}, ${r.upper}]")
    }
}
