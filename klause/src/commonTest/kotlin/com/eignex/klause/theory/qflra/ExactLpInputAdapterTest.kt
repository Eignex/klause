package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.formats.smtlib.exactLpModel
import com.eignex.klause.formats.smtlib.toExactLpModel
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Bits
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExactLpInputAdapterTest {
    @Test
    fun `theory adapter retains wide rows and IEEE source bounds`() {
        val wide = BigInteger.ONE shl 80
        val model = Problem(
            numBoolVars = 0,
            intBounds = openBounds(1),
            factors = arrayOf(Linear(intArrayOf(0), arrayOf(wide), LinearOp.LE, wide + BigInteger.ONE)),
            numRealVars = 1,
            realLower = doubleArrayOf(-0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )

        val exact = QfLraSystem(model).build { null }.toExactLpModel()

        assertEquals((-0.0).toRawBits(), exact.column(0).bounds.lower?.number?.ieeeBits)
        assertFalse(exact.column(0).integral)
        assertTrue(exact.column(1).integral)
        assertEquals(wide.toString(), exact.entries(1).single().number.value.toString())
        assertEquals((wide + BigInteger.ONE).toString(), exact.rhs(0).value.toString())
        assertNull(exact.toLegacy())
    }

    @Test
    fun `strict activated theory rows retain their source premise`() {
        val row = ReifiedRealLinear(
            aux = 0,
            vars = intArrayOf(),
            intCoeffs = doubleArrayOf(),
            realVars = intArrayOf(0),
            realCoeffs = doubleArrayOf(1.0),
            op = LinearOp.LE,
            bound = 0.5,
            strict = true,
        )
        val model = Problem(
            numBoolVars = 1,
            intBounds = openBounds(0),
            factors = arrayOf(row),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )

        val exact = QfLraSystem(model).build { true }.toExactLpModel()

        assertTrue(exact.row(0).strict)
        assertFalse(exact.row(0).global)
        assertNotNull(exact.row(0).premises)
        assertNull(exact.toLegacy())
    }

    @Test
    fun `native exact adapter retains one third and projected equal bounds`() {
        val third = BigFraction.of(BigInteger.ONE, BigInteger.fromInt(3))
        val one = ExactLpSourceNumber(BigFraction.ONE)
        val next = ExactLpSourceNumber(BigFraction.ONE + BigFraction.of(BigInteger.ONE, BigInteger.ONE shl 54))
        val model = exactLpModel(
            listOf(
                ExactLpSourceColumn(one, next, ExactLpSourceNumber(BigFraction.ZERO), integral = false, tag = 4),
            ),
            listOf(
                ExactLpSourceRow(
                    ExactRationalInequality(intArrayOf(0), listOf(third), third, strict = false),
                ),
            ),
        )

        assertEquals(one.value.toDouble(), next.value.toDouble())
        assertFalse(model.column(0).bounds.fixed)
        assertEquals(third, model.entries(0).single().number.value)
        assertEquals(third, model.rhs(0).value)
        assertEquals(4, model.column(0).tag)
        assertNull(model.toLegacy())
    }

    @Test
    fun `compatible activated row keeps the asserted literal premise`() {
        val model = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0L), longArrayOf(10L), null, null),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 4)),
        )

        val legacy = assertNotNull(QfLraSystem(model).build { true }.toExactLpModel().toLegacy())

        assertFalse(legacy.rowGlobal.single())
        assertEquals(listOf(Lit.make(0, true)), assertNotNull(legacy.rowPremises.single()).boolLits.toList())
    }

    private fun openBounds(size: Int): IntBounds {
        val open = Bits(size).also { bits -> for (i in 0 until size) bits.set(i) }
        return IntBounds.fromModelBounds(LongArray(size), LongArray(size), open, open.copy())
    }
}
