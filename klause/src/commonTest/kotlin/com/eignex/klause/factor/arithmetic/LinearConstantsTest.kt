package com.eignex.klause.factor.arithmetic

import com.eignex.klause.solver.VarRemap
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `a real form with no continuous term is refused rather than read as an integer row`() {
        // The real constructors pass empty integer terms for the shape they do not use; without a
        // continuous term the row would fall through to the integer shape and read those as its own.
        assertFailsWith<IllegalArgumentException> {
            Linear(
                intVars = intArrayOf(0),
                intCoeffs = doubleArrayOf(3.0),
                realVars = IntArray(0),
                realCoeffs = DoubleArray(0),
                op = LinearOp.LE,
                bound = 5.0,
            )
        }
    }

    @Test
    fun `a real row rejects nonfinite constants`() {
        assertFailsWith<IllegalArgumentException> {
            Linear(
                intVars = intArrayOf(0),
                intCoeffs = doubleArrayOf(Double.NaN),
                realVars = intArrayOf(0),
                realCoeffs = doubleArrayOf(1.0),
                op = LinearOp.LE,
                bound = 5.0,
            )
        }
    }

    @Test
    fun `a wide row requires distinct variables`() {
        val coefficient = BigInteger.ONE

        assertFailsWith<IllegalArgumentException> {
            Linear(intArrayOf(0, 0), arrayOf(coefficient, coefficient), LinearOp.LE, coefficient)
        }
        assertFailsWith<IllegalArgumentException> {
            ReifiedLinear(0, intArrayOf(0, 0), arrayOf(coefficient, coefficient), LinearOp.LE, coefficient)
        }
    }

    @Test
    fun `a collapsing wide remap retains a constant row`() {
        val coefficient = BigInteger.fromLong(Long.MAX_VALUE) * 4
        val map = VarRemap(intArrayOf(0), intArrayOf(0, 0))
        val linear = Linear(intArrayOf(0, 1), arrayOf(coefficient, -coefficient), LinearOp.EQ, BigInteger.ZERO)
        val reified = ReifiedLinear(0, intArrayOf(0, 1), arrayOf(coefficient, -coefficient), LinearOp.EQ, BigInteger.ZERO)

        val remappedLinear = assertIs<Linear>(linear.remap(map))
        val remappedReified = assertIs<ReifiedLinear>(reified.remap(map))

        assertEquals(BigInteger.ZERO, checkNotNull(remappedLinear.wideConstants).coefficients.at(0))
        assertEquals(BigInteger.ZERO, checkNotNull(remappedReified.wideConstants).coefficients.at(0))
    }
}
