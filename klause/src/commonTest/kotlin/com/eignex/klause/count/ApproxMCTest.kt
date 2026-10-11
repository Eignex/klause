package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
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

    @Test
    fun `unconstrained reals are existential in public projected counting`() {
        for (max in listOf(1L, 2L)) {
            val model = Problem(
                numBoolVars = 1, numIntVars = 1, intDomains = arrayOf(IntDomain(1, max)),
                factors = emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(2.0), realUpper = doubleArrayOf(3.0),
            )

            val count = BacktrackSolver(model.bake()).approximateCount(ApproxCountConfig(seed = 0L))

            assertTrue(count.exact)
            assertEquals(2 * max, count.estimate)
        }
    }

    @Test
    fun `real constraints exclude infeasible integer projections in public counting`() {
        for (max in listOf(1L, 2L)) {
            val model = Problem(
                numBoolVars = 1, numIntVars = 1, intDomains = arrayOf(IntDomain(1, max)),
                factors = arrayOf(
                    Clause(intArrayOf(Lit.make(0, true))),
                    ReifiedRealLinear(0, intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0),
                        doubleArrayOf(3.0), LinearOp.LE, 0.0),
                    ReifiedRealLinear(0, intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(0),
                        doubleArrayOf(3.0), LinearOp.GE, 0.0),
                ),
                numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.5),
            )

            val count = BacktrackSolver(model.bake()).approximateCount(
                ApproxCountConfig(samplingSet = intArrayOf(), intSamplingSet = intArrayOf(0), seed = 0L),
            )

            assertTrue(count.exact)
            assertEquals(1L, count.estimate)
        }
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
