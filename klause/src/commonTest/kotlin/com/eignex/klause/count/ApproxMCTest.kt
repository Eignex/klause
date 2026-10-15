package com.eignex.klause.count

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApproxMCTest {

    @Test
    fun `hash scaled cell counts should saturate beyond the public count range`() {
        val cases = listOf(
            Triple(3, 60, 3L shl 60),
            Triple(2, 62, Long.MAX_VALUE),
            Triple(1, 63, Long.MAX_VALUE),
            Triple(1, 64, Long.MAX_VALUE),
            Triple(0, 64, 0L),
        )

        for ((count, hashes, expected) in cases) {
            assertEquals(expected, ApproxMC.scaleCellCount(count, hashes), "count=$count hashes=$hashes")
        }
    }

    private fun unconstrained(n: Int) =
        Problem(numBoolVars = n, numIntVars = 0, intDomains = emptyArray(), factors = arrayOf<Factor>())

    @Test
    fun `small problem should be counted exactly without hashing`() {
        val p = unconstrained(3)
        val r = BacktrackSolver(p.bake()).approximateCount(ApproxCountConfig(seed = 0L))
        assertTrue(r.exact)
        assertEquals(8L, r.estimate)
    }

    @Test
    fun `projected count should count a subset of variables`() {
        // 16 reachable projections stay below the cell threshold, so the count is exact.
        val p = unconstrained(6)
        val r = BacktrackSolver(p.bake()).approximateCount(
            ApproxCountConfig(epsilon = 0.8, delta = 0.35, samplingSet = intArrayOf(0, 1, 2, 3), seed = 5L),
        )
        assertWithinBand(16L, r.estimate, 0.8)
    }

    @Test
    fun `unsat instance should count zero`() {
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
    fun `unconstrained reals should be existential in projected counting`() {
        for (max in listOf(1L, 2L)) {
            val model = Problem(
                numBoolVars = 1,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(1, max)),
                factors = emptyArray(),
                numRealVars = 2,
                realLower = doubleArrayOf(2.0, Double.NEGATIVE_INFINITY),
                realUpper = doubleArrayOf(3.0, Double.POSITIVE_INFINITY),
            )

            val count = BacktrackSolver(model.bake()).approximateCount(ApproxCountConfig(seed = 0L))

            assertTrue(count.exact)
            assertEquals(2 * max, count.estimate)
        }
    }

    @Test
    fun `real constraints should exclude infeasible integer projections in counting`() {
        for (max in listOf(1L, 2L)) {
            val model = Problem(
                numBoolVars = 1,
                numIntVars = 1,
                intDomains = arrayOf(IntDomain(1, max)),
                factors = arrayOf(
                    Clause(intArrayOf(Lit.make(0, true))),
                    ReifiedRealLinear(
                        0,
                        intArrayOf(0),
                        doubleArrayOf(-1.0),
                        intArrayOf(0),
                        doubleArrayOf(3.0),
                        LinearOp.LE,
                        0.0,
                    ),
                    ReifiedRealLinear(
                        0,
                        intArrayOf(0),
                        doubleArrayOf(-1.0),
                        intArrayOf(0),
                        doubleArrayOf(3.0),
                        LinearOp.GE,
                        0.0,
                    ),
                ),
                numRealVars = 1,
                realLower = doubleArrayOf(0.0),
                realUpper = doubleArrayOf(0.5),
            )

            val count = BacktrackSolver(model.bake()).approximateCount(
                ApproxCountConfig(samplingSet = intArrayOf(), intSamplingSet = intArrayOf(0), seed = 0L),
            )

            assertTrue(count.exact)
            assertEquals(1L, count.estimate)
        }
    }

    @Test
    fun `empty real interval should have zero projected models`() {
        for (max in listOf(1L, 2L)) {
            val model = Problem(
                0,
                1,
                arrayOf(IntDomain(1, max)),
                emptyArray(),
                numRealVars = 1,
                realLower = doubleArrayOf(2.0),
                realUpper = doubleArrayOf(1.0),
            )

            val count = BacktrackSolver(model.bake()).approximateCount(ApproxCountConfig(seed = 0L))

            assertTrue(count.exact)
            assertEquals(0L, count.estimate)
        }
    }

    @Test
    fun `conditioned mixed counts should restore Boolean pins and integer holes`() {
        val model = Problem(
            1,
            1,
            arrayOf(IntDomain(0, 2)),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(2.0),
            realUpper = doubleArrayOf(3.0),
        ).bake()
        val config = ApproxCountConfig(seed = 0L)
        BacktrackSolver(model).session().use { session ->
            session.push(Assumptions(bools = mapOf(0 to true)).withIntHole(0, 1L))
            assertEquals(2L, session.approximateCount(config).estimate)
            session.push(Assumptions(ints = mapOf(0 to 2L)))
            assertEquals(1L, session.approximateCount(config).estimate)
            session.pop()
            assertEquals(2L, session.approximateCount(config).estimate)
            session.pop()
            assertEquals(6L, session.approximateCount(config).estimate)
        }
        assertEquals(3L, model.rootIntDomain(0).valueCount)
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
