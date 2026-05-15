package com.eignex.klause.solver

import com.eignex.klause.solver.factor.FloatLinear
import com.eignex.klause.solver.factor.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FloatLoweringTest {

    @Test
    fun `no-op when problem has no floats`() {
        val p = Problem(numBoolVars = 1, numIntVars = 0, intDomains = emptyArray(), factors = emptyList())
        val l = FloatLowering.lower(p)
        assertEquals(0, l.problem.numFloatVars)
        assertEquals(p.numIntVars, l.problem.numIntVars)
        assertEquals(p.factors.size, l.problem.factors.size)
        // Decoder roundtrip preserves the sample unchanged.
        val s = Sample(BooleanArray(1) { true }, IntArray(0), DoubleArray(0))
        assertEquals(s, l.decoder.decode(s))
    }

    @Test
    fun `lowering allocates one int var per float and rewrites linear factors`() {
        // Two floats over [0.0, 1.0]; constraint x + y <= 1.0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = listOf(
                FloatLinear(
                    coeffs = doubleArrayOf(1.0, 1.0),
                    vars = intArrayOf(0, 1),
                    op = LinearOp.LE,
                    bound = 1.0,
                ),
            ),
            numFloatVars = 2,
            floatDomains = arrayOf(FloatInterval(0.0, 1.0), FloatInterval(0.0, 1.0)),
        )
        val l = FloatLowering.lower(problem, buckets = 11)  // 11 buckets → step 0.1
        assertEquals(2, l.problem.numIntVars)
        assertEquals(IntDomain(0, 10), l.problem.intDomains[0])
        assertEquals(0, l.problem.numFloatVars)
        assertEquals(1, l.problem.factors.size)
    }

    @Test
    fun `decode recovers float values within bucket resolution`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyList(),
            numFloatVars = 1,
            floatDomains = arrayOf(FloatInterval(-1.0, 1.0)),
        )
        val l = FloatLowering.lower(problem, buckets = 11)  // step = 0.2; bucket 5 → 0.0

        val sampleAtMid = Sample(BooleanArray(0), IntArray(1) { 5 }, DoubleArray(0))
        val decoded = l.decoder.decode(sampleAtMid)
        assertEquals(1, decoded.floats.size)
        assertTrue(decoded.floats[0] in -0.05..0.05, "bucket 5 should decode near 0.0, got ${decoded.floats[0]}")
    }
}
