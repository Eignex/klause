package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.UnitConsts
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.ir.linearRows
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinearTest {

    @Test
    fun `canonicalising a wide greater-equal row should negate its exact constants`() {
        val huge = bigIntOf(Long.MAX_VALUE) * bigIntOf(4)

        val row = Linear(intArrayOf(0), arrayOf(huge), LinearOp.GE, huge)

        val constants = assertIs<WideConstants>(row.constants)
        assertEquals(-huge, constants.bound)
        assertEquals(-huge, constants.coefficients.at(0))
    }

    @Test
    fun `factor rows should distinguish strict real and wide constants`() {
        val strictReal = Linear(
            intVars = intArrayOf(0),
            intCoeffs = doubleArrayOf(0.5),
            realVars = intArrayOf(1),
            realCoeffs = doubleArrayOf(1.0),
            op = LinearOp.LE,
            bound = 2.5,
            strict = true,
        )
        val huge = bigIntOf(Long.MAX_VALUE) * bigIntOf(4)
        val wide = Linear(intArrayOf(0), arrayOf(huge), LinearOp.LE, huge)

        val realRow = strictReal.linearRows.single()
        val wideRow = wide.linearRows.single()

        assertTrue(realRow.strict)
        assertEquals(huge, assertIs<WideConstants>(wideRow.constants).exactCoeff(0))
        assertEquals(huge, assertIs<WideConstants>(wideRow.constants).bound)
    }

    @Test
    fun `rows should require one coefficient per term`() {
        assertFailsWith<IllegalArgumentException> {
            TaggedLinearRow(intArrayOf(), IntegerConstants(UnitConsts(1), 1L), LinearOp.LE)
        }
    }

    @Test
    fun `a real form with no continuous term should be refused rather than read as an integer row`() {
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
    fun `a real row should reject nonfinite constants`() {
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
    fun `a wide row should require distinct variables`() {
        val coefficient = BIG_ONE

        assertFailsWith<IllegalArgumentException> {
            Linear(intArrayOf(0, 0), arrayOf(coefficient, coefficient), LinearOp.LE, coefficient)
        }
        assertFailsWith<IllegalArgumentException> {
            ReifiedLinear(0, intArrayOf(0, 0), arrayOf(coefficient, coefficient), LinearOp.LE, coefficient)
        }
    }

    @Test
    fun `a collapsing wide remap should retain a constant row`() {
        val coefficient = bigIntOf(Long.MAX_VALUE) * bigIntOf(4)
        val map = VarRemap(intArrayOf(0), intArrayOf(0, 0))
        val linear = Linear(
            intArrayOf(0, 1),
            arrayOf(coefficient, -coefficient),
            LinearOp.EQ,
            BIG_ZERO,
        )
        val reified = ReifiedLinear(
            0,
            intArrayOf(0, 1),
            arrayOf(coefficient, -coefficient),
            LinearOp.EQ,
            BIG_ZERO,
        )

        val remappedLinear = assertIs<Linear>(linear.remap(map))
        val remappedReified = assertIs<ReifiedLinear>(reified.remap(map))

        assertEquals(BIG_ZERO, checkNotNull(remappedLinear.wideConstants).coefficients.at(0))
        assertEquals(BIG_ZERO, checkNotNull(remappedReified.wideConstants).coefficients.at(0))
    }

    @Test
    fun `coalescing coefficients past Int range should keep the exact Long sum`() {
        val big = Int.MAX_VALUE.toLong()
        val linear = Linear(longArrayOf(big, big), intArrayOf(0, 0), LinearOp.LE, 5L)
        assertTrue(checkNotNull(linear.integerConstants).coeffs.contentEquals(longArrayOf(2 * big)))
        assertTrue(linear.vars.contentEquals(intArrayOf(0)))
        assertEquals(5L, checkNotNull(linear.integerConstants).bound)
    }

    @Test
    fun `coalescing coefficients beyond Long range should be refused`() {
        assertFailsWith<IllegalArgumentException> {
            Linear(longArrayOf(Long.MAX_VALUE, 1L), intArrayOf(0, 0), LinearOp.LE, 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            ReifiedLinear(0, longArrayOf(Long.MIN_VALUE, -1L), intArrayOf(0, 0), LinearOp.LE, 0L)
        }
    }
}
