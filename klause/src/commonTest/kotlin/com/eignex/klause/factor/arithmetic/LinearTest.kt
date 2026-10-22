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
    fun `greater-equal canonicalisation should preserve exact signed word endpoints`() {
        for (coefficient in listOf(Long.MIN_VALUE, Long.MIN_VALUE + 2, -2L, 2L)) {
            for (bound in listOf(Long.MIN_VALUE, Long.MIN_VALUE + 2, -3L, 0L, 3L)) {
                val row = Linear(longArrayOf(coefficient), intArrayOf(0), LinearOp.GE, bound)

                val constants = checkNotNull(row.integralConstants)
                assertEquals(LinearOp.LE, row.op)
                assertEquals(-bigIntOf(coefficient), constants.exactCoeff(0))
                assertEquals(-bigIntOf(bound), constants.exactBound)
                if (coefficient == Long.MIN_VALUE || bound == Long.MIN_VALUE) {
                    assertIs<WideConstants>(constants)
                } else {
                    assertIs<IntegerConstants>(constants)
                }
            }
        }
    }

    @Test
    fun `promoted greater-equal rows should share keys with their exact negated forms`() {
        for ((coefficient, bound) in listOf(2L to Long.MIN_VALUE, Long.MIN_VALUE to 0L,
            Long.MIN_VALUE to Long.MIN_VALUE)) {
            val row = Linear(longArrayOf(coefficient), intArrayOf(0), LinearOp.GE, bound)
            val negated = Linear(intArrayOf(0), arrayOf(-bigIntOf(coefficient)), LinearOp.LE, -bigIntOf(bound))
            val mapping = VarRemap(intArrayOf(), intArrayOf(1))

            assertEquals(negated.structuralKey(), row.structuralKey())
            assertEquals(negated.remap(mapping).structuralKey(), row.remap(mapping).structuralKey())
            assertEquals(row.remap(mapping).structuralKey().hashCode(), row.remapStructuralHash(mapping))
        }
    }

    @Test
    fun `rows without canonical negation should retain minimum long constants`() {
        for (op in listOf(LinearOp.LE, LinearOp.EQ, LinearOp.NE)) {
            val row = Linear(longArrayOf(Long.MIN_VALUE), intArrayOf(0), op, Long.MIN_VALUE)

            val constants = assertIs<IntegerConstants>(row.constants)
            assertEquals(op, row.op)
            assertEquals(Long.MIN_VALUE, constants.coeff(0))
            assertEquals(Long.MIN_VALUE, constants.bound)
        }
    }

    @Test
    fun `canonicalisation should promote a coefficient coalesced to the minimum long`() {
        val row = Linear(longArrayOf(Long.MIN_VALUE + 2, -2, 3), intArrayOf(0, 0, 1), LinearOp.GE, 0L)

        val constants = assertIs<WideConstants>(row.constants)
        assertEquals(listOf(0, 1), row.vars.toList())
        assertEquals(-bigIntOf(Long.MIN_VALUE), constants.exactCoeff(0))
        assertEquals(bigIntOf(-3), constants.exactCoeff(1))
    }

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
