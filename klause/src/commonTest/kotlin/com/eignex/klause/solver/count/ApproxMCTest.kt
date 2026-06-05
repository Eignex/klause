package com.eignex.klause.solver.count

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApproxMCTest {

    private fun unconstrained(n: Int) =
        Problem(numBoolVars = n, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>())

    private fun exactCount(p: Problem): Long = BacktrackSolver(p).enumerate(BacktrackParams()).count().toLong()

    @Test
    fun `small problem is counted exactly without hashing`() {
        val p = unconstrained(3) // 2^3 = 8 models, below the threshold
        val r = BacktrackSolver(p).approximateCount(ApproxCountConfig(seed = 0L))
        assertTrue(r.exact, "small instance should short-circuit to an exact count")
        assertEquals(8L, r.estimate)
    }

    @Test
    fun `large free instance is within the epsilon band of the exact count`() {
        val p = unconstrained(10) // 1024 models — forces XOR hashing
        val exact = exactCount(p)
        val eps = 0.8
        val r = BacktrackSolver(p).approximateCount(
            ApproxCountConfig(epsilon = eps, delta = 0.01, seed = 12345L),
        )
        assertTrue(!r.exact, "instance should require hashing")
        assertWithinBand(exact, r.estimate, eps)
    }

    @Test
    fun `constrained instance is within the epsilon band`() {
        // 8 free vars, one clause (x0 v x1) removes the 2^6 assignments with x0=x1=false.
        val n = 8
        val p = Problem(
            numBoolVars = n,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val exact = exactCount(p)
        val eps = 0.8
        val r = BacktrackSolver(p).approximateCount(
            ApproxCountConfig(epsilon = eps, delta = 0.01, seed = 999L),
        )
        assertWithinBand(exact, r.estimate, eps)
    }

    @Test
    fun `projected count over a subset of variables`() {
        // 10 vars, project onto the first 6: every projection is reachable -> 2^6 = 64.
        val p = unconstrained(10)
        val r = BacktrackSolver(p).approximateCount(
            ApproxCountConfig(epsilon = 0.8, delta = 0.01, samplingSet = intArrayOf(0, 1, 2, 3, 4, 5), seed = 5L),
        )
        assertWithinBand(64L, r.estimate, 0.8)
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
        val r = BacktrackSolver(p).approximateCount(ApproxCountConfig(seed = 0L))
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
