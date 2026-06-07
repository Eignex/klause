package com.eignex.klause.solver.count

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExactCountTest {

    private fun freeBools(n: Int) =
        Problem(numBoolVars = n, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>())

    @Test
    fun `exact count of a small free problem`() {
        val r = BacktrackSolver(freeBools(3)).exactCount().last()
        assertTrue(r.exact)
        assertEquals(8L, r.lower)
        assertEquals(8L, r.upper)
        assertEquals(8L, r.estimate)
        assertEquals(1.0, r.confidence)
    }

    @Test
    fun `exact count matches enumeration on a constrained problem`() {
        // (x0 ∨ x1) over 3 bools → 8 - 1 (all-false) = 7 models.
        val p = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val truth = BacktrackSolver(p).enumerate(BacktrackParams()).count().toLong()
        val r = BacktrackSolver(p).exactCount().last()
        assertTrue(r.exact)
        assertEquals(truth, r.lower)
    }

    @Test
    fun `exact projected count treats non-projection vars existentially`() {
        // 5 free bools, project onto the first 2 → 4 distinct projections (others free).
        val r = BacktrackSolver(freeBools(5)).exactCount(ExactCountConfig(samplingSet = intArrayOf(0, 1))).last()
        assertTrue(r.exact)
        assertEquals(4L, r.lower)
    }

    @Test
    fun `exact projected count over integer variables`() {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(),
        )
        val r = BacktrackSolver(p).exactCount().last()
        assertTrue(r.exact)
        assertEquals(16L, r.lower)
    }

    @Test
    fun `unsat problem counts exactly zero`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val r = BacktrackSolver(p).exactCount().last()
        assertTrue(r.exact)
        assertEquals(0L, r.estimate)
    }

    @Test
    fun `bounds tighten monotonically and bracket the true count`() {
        val truth = 64L // 2^6
        val snaps = BacktrackSolver(freeBools(6))
            .exactCount(ExactCountConfig(reportEvery = 4)).toList()
        assertTrue(snaps.size >= 2, "expected several anytime snapshots")
        for (i in 1 until snaps.size) {
            assertTrue(snaps[i].lower >= snaps[i - 1].lower, "lower must not decrease")
            assertTrue(snaps[i].upper <= snaps[i - 1].upper, "upper must not increase")
        }
        for (s in snaps) {
            assertTrue(s.lower <= truth && truth <= s.upper, "interval [${s.lower},${s.upper}] must bracket $truth")
        }
        assertTrue(snaps.last().exact)
        assertEquals(truth, snaps.last().lower)
    }

    @Test
    fun `budget-capped exact count is inexact but still brackets the true count`() {
        // Stop after very few checks: not exact, but [lower, upper] must still contain 1024.
        val r = BacktrackSolver(freeBools(10)).exactCount(ExactCountConfig(maxChecks = 5)).last()
        assertTrue(!r.exact, "should not finish within 5 checks")
        assertTrue(r.lower <= 1024L && 1024L <= r.upper, "interval [${r.lower},${r.upper}] must bracket 1024")
    }

    @Test
    fun `hybrid count is exact when cheap`() {
        val r = BacktrackSolver(freeBools(3)).count()
        assertTrue(r.exact)
        assertEquals(8L, r.estimate)
    }

    @Test
    fun `hybrid count falls back to approximate when exact budget is tiny`() {
        // 128 models: the smallest free instance ApproxMC still hashes (cell threshold ≈ 73),
        // so the fallback runs its full pipeline at the cheapest possible enumeration cost.
        val r = BacktrackSolver(freeBools(7)).count(CountConfig(exactBudget = 5, delta = 0.35, seed = 7L))
        // Fell back to ApproxMC: a probabilistic estimate near 128, bracketed and confidence < 1.
        val lo = 128 / 1.8
        val hi = 128 * 1.8
        assertTrue(r.estimate in lo.toLong()..hi.toLong(), "estimate ${r.estimate} outside [$lo,$hi]")
        assertTrue(r.lower <= r.upper)
        assertTrue(r.confidence < 1.0)
    }
}
