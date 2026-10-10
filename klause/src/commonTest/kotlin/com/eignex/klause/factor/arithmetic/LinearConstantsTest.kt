package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
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

class LinearConstantsTest {

    @Test
    fun `canonicalising a wide greater-equal row negates its exact constants`() {
        val huge = bigIntOf(Long.MAX_VALUE) * bigIntOf(4)

        val row = Linear(intArrayOf(0), arrayOf(huge), LinearOp.GE, huge)

        val constants = assertIs<WideConstants>(row.constants)
        assertEquals(-huge, constants.bound)
        assertEquals(-huge, constants.coefficients.at(0))
    }

    @Test
    fun `factor rows retain exact finite constants and reification`() {
        val unconditional = Linear(longArrayOf(Long.MAX_VALUE), intArrayOf(2), LinearOp.LE, Long.MAX_VALUE)
        val reified = ReifiedLinear(4, longArrayOf(-7), intArrayOf(3), LinearOp.GE, 9)

        val plainRow = unconditional.linearRows.single()
        val reifiedRow = reified.linearRows.single()

        assertEquals(LinearRow.ALWAYS, plainRow.activator)
        assertEquals(Long.MAX_VALUE, plainRow.coeff(0))
        assertEquals(Long.MAX_VALUE, plainRow.bound)
        assertEquals(4, reifiedRow.activator)
        assertEquals(-7L, reifiedRow.coeff(0))
        assertEquals(9L, reifiedRow.bound)
    }

    @Test
    fun `factor rows distinguish strict real and wide constants`() {
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
    fun `rows require one coefficient per term`() {
        assertFailsWith<IllegalArgumentException> {
            TaggedLinearRow(intArrayOf(), IntegerConstants(UnitConsts(1), 1L), LinearOp.LE)
        }
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
        val coefficient = BIG_ONE

        assertFailsWith<IllegalArgumentException> {
            Linear(intArrayOf(0, 0), arrayOf(coefficient, coefficient), LinearOp.LE, coefficient)
        }
        assertFailsWith<IllegalArgumentException> {
            ReifiedLinear(0, intArrayOf(0, 0), arrayOf(coefficient, coefficient), LinearOp.LE, coefficient)
        }
    }

    @Test
    fun `a collapsing wide remap retains a constant row`() {
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
}
