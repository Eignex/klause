package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.parseBigInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WideLinearRelaxationTest {
    @Test
    fun `wide ordinary and objective comparisons retain exact normalized source ratios`() {
        val tiny = BigFraction.of(BIG_ONE, parseBigInt("100000000000000000000"))
        for (relation in listOf(LinearOp.LE, LinearOp.GE, LinearOp.EQ)) {
            val source = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
                listOf(BigFraction.ONE, tiny), relation, BigFraction.ofLong(3))

            val row = assertNotNull(source.numericalRow())

            assertEquals(tiny, row.coefficients[1] * row.coefficients[0].reciprocal())
            assertEquals(BigFraction.ofLong(3), row.rhs * row.coefficients[0].reciprocal())
        }
    }

    @Test
    fun `a nonzero coefficient lost by floating projection declines admission`() {
        val tiny = BigFraction.of(BIG_ONE, parseBigInt("1" + "0".repeat(400)))
        val source = ExactLinear.of(intArrayOf(Term.ofIntVar(0), Term.ofRealVar(0)),
            listOf(BigFraction.ONE, tiny), LinearOp.LE, BigFraction.ONE)

        val row = source.numericalRow()

        assertNull(row)
        assertFalse(source.finiteRelaxationSupported())
    }

    @Test
    fun `disequality does not acquire numerical completion`() {
        val source = ExactLinear.of(intArrayOf(Term.ofRealVar(0)),
            listOf(BigFraction.ONE), LinearOp.NE, BigFraction.ZERO)

        assertNull(source.numericalRow())
    }
}
