package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.factor.arithmetic.LinearOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WeightedSumFactorTest {

    @Test
    fun `linearHolds LE satisfied at boundary`() {
        assertTrue(linearHolds(5L, LinearOp.LE, 5))
        assertFalse(linearHolds(6L, LinearOp.LE, 5))
    }

    @Test
    fun `linearHolds GE satisfied at boundary`() {
        assertTrue(linearHolds(5L, LinearOp.GE, 5))
        assertFalse(linearHolds(4L, LinearOp.GE, 5))
    }

    @Test
    fun `linearHolds EQ exact match only`() {
        assertTrue(linearHolds(5L, LinearOp.EQ, 5))
        assertFalse(linearHolds(6L, LinearOp.EQ, 5))
        assertFalse(linearHolds(4L, LinearOp.EQ, 5))
    }

    @Test
    fun `linearHolds NE any value except bound`() {
        assertFalse(linearHolds(5L, LinearOp.NE, 5))
        assertTrue(linearHolds(6L, LinearOp.NE, 5))
        assertTrue(linearHolds(4L, LinearOp.NE, 5))
    }

    @Test
    fun `linearDegree matches holds plus residual for all ops`() {
        val cap = 16
        for ((sum, op, bound) in listOf(
            Triple(3L, LinearOp.LE, 5),
            Triple(7L, LinearOp.LE, 5),
            Triple(3L, LinearOp.GE, 5),
            Triple(7L, LinearOp.GE, 5),
            Triple(5L, LinearOp.EQ, 5),
            Triple(8L, LinearOp.EQ, 5),
            Triple(5L, LinearOp.NE, 5),
            Triple(6L, LinearOp.NE, 5),
        )) {
            val expected = if (linearHolds(sum, op, bound)) 0 else linearResidual(sum, op, bound, cap)
            assertEquals(expected, linearDegree(sum, op, bound, cap), "op=$op sum=$sum bound=$bound")
        }
    }

    @Test
    fun `coalesceLinearTerms sums duplicate variable coefficients`() {
        val terms = coalesceLinearTerms(intArrayOf(0, 1, 0), intArrayOf(2, 3, 4))
        assertEquals(2, terms.vars.size)
        val idx0 = terms.vars.indexOf(0)
        val idx1 = terms.vars.indexOf(1)
        assertEquals(6, terms.coeffs[idx0], "duplicate var 0 coeffs 2+4 should coalesce to 6")
        assertEquals(3, terms.coeffs[idx1], "unique var 1 coefficient should stay 3")
    }

    @Test
    fun `coalesceLinearTerms returns same arrays when no duplicates`() {
        val vars = intArrayOf(0, 1, 2)
        val coeffs = intArrayOf(1, 2, 3)
        val terms = coalesceLinearTerms(vars, coeffs)
        assertSame(vars, terms.vars, "no duplicate: vars array should be the same reference")
        assertSame(coeffs, terms.coeffs, "no duplicate: coeffs array should be the same reference")
    }
}
