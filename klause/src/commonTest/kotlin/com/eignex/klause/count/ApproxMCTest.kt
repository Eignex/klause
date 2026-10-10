package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApproxMCTest {

    private fun unconstrained(n: Int) =
        Problem(numBoolVars = n, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>())

    @Test
    fun `small problem is counted exactly without hashing`() {
        val p = unconstrained(3) // 8 models, below the hashing threshold
        val r = BacktrackSolver(p.bake()).approximateCount(ApproxCountConfig(seed = 0L))
        assertTrue(r.exact, "small instance should short-circuit to an exact count")
        assertEquals(8L, r.estimate)
    }

    @Test
    fun `projected count over a subset of variables`() {
        // Project 6 vars onto the first 4: 2^4 = 16 reachable projections, below the cell
        // threshold so the projection short-circuits to an exact enumeration.
        val p = unconstrained(6)
        val r = BacktrackSolver(p.bake()).approximateCount(
            ApproxCountConfig(epsilon = 0.8, delta = 0.35, samplingSet = intArrayOf(0, 1, 2, 3), seed = 5L),
        )
        assertWithinBand(16L, r.estimate, 0.8)
    }

    @Test
    fun `unsat instance counts zero`() {
        val p = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val r = BacktrackSolver(p.bake()).approximateCount(ApproxCountConfig(seed = 0L))
        assertEquals(0L, r.estimate)
        assertTrue(r.exact)
    }

    private fun assertWithinBand(exact: Long, estimate: Long, eps: Double) {
        val lo = exact / (1.0 + eps)
        val hi = exact * (1.0 + eps)
        assertTrue(
            estimate >= lo && estimate <= hi,
            "estimate $estimate outside (1±$eps) band [$lo, $hi] of exact $exact",
        )
    }
}
