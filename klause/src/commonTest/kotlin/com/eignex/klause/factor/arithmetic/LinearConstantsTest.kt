package com.eignex.klause.factor.arithmetic

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A linear row's constants have the shape the row has, so a consumer reads exact values or nothing.
 */
class LinearConstantsTest {

    @Test
    fun `a plain integer row reads as 64-bit arithmetic`() {
        val row = Linear(intArrayOf(3, -2), intArrayOf(0, 1), LinearOp.LE, 10)

        val constants = assertIs<IntegerConstants>(row.constants)
        assertEquals(10L, constants.bound)
        assertEquals(3L, constants.coeff(0))
        assertEquals(3L, constants.maxAbsCoeff)
    }

    @Test
    fun `an over-64-bit row has no integer reading`() {
        val huge = BigInteger.fromLong(Long.MAX_VALUE) * 4

        val row = Linear(intArrayOf(0), arrayOf(huge), LinearOp.LE, huge)

        assertNull(row.integerConstants, "a wide row has no 64-bit constants to read")
        assertEquals(huge, assertIs<WideConstants>(row.constants).bound)
        assertEquals(huge, checkNotNull(row.integralConstants).exactCoeff(0))
    }

    @Test
    fun `a continuous row has neither an integer nor an exact-integer reading`() {
        val row = Linear(
            intVars = intArrayOf(0),
            intCoeffs = doubleArrayOf(0.5),
            realVars = intArrayOf(0),
            realCoeffs = doubleArrayOf(1.0),
            op = LinearOp.LE,
            bound = 2.5,
        )

        assertNull(row.integerConstants)
        assertNull(row.integralConstants, "a fractional coefficient has no exact integer reading")
        assertEquals(2.5, assertIs<RealConstants>(row.constants).bound)
    }

    @Test
    fun `canonicalising a greater-equal row negates the constants once`() {
        val row = Linear(intArrayOf(2, -3), intArrayOf(0, 1), LinearOp.GE, 4)

        val constants = assertIs<IntegerConstants>(row.constants)
        assertEquals(LinearOp.LE, row.op)
        assertEquals(-4L, constants.bound)
        assertEquals(-2L, constants.coeff(0))
        assertEquals(3L, constants.coeff(1))
    }

    @Test
    fun `canonicalising a wide greater-equal row negates its exact constants`() {
        val huge = BigInteger.fromLong(Long.MAX_VALUE) * 4

        val row = Linear(intArrayOf(0), arrayOf(huge), LinearOp.GE, huge)

        val constants = assertIs<WideConstants>(row.constants)
        assertEquals(-huge, constants.bound)
        assertEquals(-huge, constants.coefficients.at(0))
    }

    @Test
    fun `a row exposes its integer reading as its linear row`() {
        val row = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3)

        val linearRow = row.linearRows.single()

        assertEquals(2, linearRow.size)
        assertEquals(3L, linearRow.bound)
        assertTrue(linearRow.isIntegerOnly)
    }

    @Test
    fun `a wide row exposes no linear row`() {
        val huge = BigInteger.fromLong(Long.MAX_VALUE) * 4

        val row = Linear(intArrayOf(0), arrayOf(huge), LinearOp.LE, huge)

        assertTrue(row.linearRows.isEmpty(), "no integer row may be read off wide constants")
    }

    @Test
    fun `a reified row carries the same constant shapes`() {
        val huge = BigInteger.fromLong(Long.MAX_VALUE) * 4

        val plain = ReifiedLinear(0, intArrayOf(2), intArrayOf(0), LinearOp.LE, 7)
        val wide = ReifiedLinear(0, intArrayOf(0), arrayOf(huge), LinearOp.LE, huge)

        assertEquals(7L, assertIs<IntegerConstants>(plain.constants).bound)
        assertNull(wide.integerConstants, "a wide reified row has no 64-bit constants to read")
        assertEquals(huge, wide.constants.exactBound)
    }
}
