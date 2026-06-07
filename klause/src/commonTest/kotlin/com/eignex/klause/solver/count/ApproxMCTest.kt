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
        // Deliberately the cheapest configuration that still hashes — this is a smoke of the
        // hashed pipeline, not of the (ε, δ) guarantee. ε=2 shrinks the cell threshold to ≈38
        // (128 models still exceed it) and δ=0.99 floors the iteration count; the band assert
        // is correspondingly loose, and the pinned seed keeps the outcome deterministic.
        val p = unconstrained(7)
        val exact = exactCount(p)
        val eps = 2.0
        val r = BacktrackSolver(p).approximateCount(
            ApproxCountConfig(epsilon = eps, delta = 0.99, seed = 12345L),
        )
        assertTrue(!r.exact, "instance should require hashing")
        assertWithinBand(exact, r.estimate, eps)
    }

    @Test
    fun `constrained instance is within the epsilon band`() {
        // 7 free vars, one clause (x0 v x1) removes the 2^5 assignments with x0=x1=false:
        // 96 models, above the ε=2 cell threshold (≈38) so the constrained hashed path runs
        // at the cheapest smoke configuration (see the free-instance case above).
        val n = 7
        val p = Problem(
            numBoolVars = n,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
        )
        val exact = exactCount(p)
        val eps = 2.0
        val r = BacktrackSolver(p).approximateCount(
            ApproxCountConfig(epsilon = eps, delta = 0.99, seed = 999L),
        )
        assertWithinBand(exact, r.estimate, eps)
    }

    @Test
    fun `projected count over a subset of variables`() {
        // 6 vars, project onto the first 4: every projection is reachable -> 2^4 = 16, below
        // the cell threshold so the projection short-circuits to an exact enumeration.
        val p = unconstrained(6)
        val r = BacktrackSolver(p).approximateCount(
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
