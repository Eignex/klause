package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.qflra.ExactLraAssignment
import com.eignex.klause.theory.qfnra.ExactNraAssignment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class OpenTheoryAssignmentTest {
    @Test
    fun `source reconstruction retains typed irrational real coordinates`() {
        val arithmetic = AlgebraicArithmetic(PolynomialArithmetic())
        val root = arithmetic.roots(RationalPolynomial.of(-2, 0, 1)).last()
        val base = OpenTheoryAssignment.ExactNra(ExactNraAssignment(booleanArrayOf(false), listOf(root)))

        val rebuilt = OpenTheoryAssignment.Rebuilt(base, booleanArrayOf(true), ints = null)

        assertEquals(true, rebuilt.boolValue(0))
        assertEquals(0, arithmetic.compare(root, assertNotNull(rebuilt.algebraicRealValue(0))))
        assertEquals("root(2;[-2,0,1])", rebuilt.realValue(0))
    }

    @Test
    fun `rational polynomial coordinates retain exact rational text`() {
        val arithmetic = AlgebraicArithmetic(PolynomialArithmetic())
        val third = BigFraction.ofLong(3).reciprocal()
        val assignment = OpenTheoryAssignment.ExactNra(
            ExactNraAssignment(BooleanArray(0), listOf(arithmetic.rational(third))),
        )

        val text = assignment.realValue(0)

        assertEquals("1/3", text)
        assertEquals(third, assignment.algebraicRealValue(0)?.rational)
    }

    @Test
    fun `linear real witnesses retain their rational compatibility surface`() {
        val third = BigFraction.ofLong(3).reciprocal()
        val assignment = OpenTheoryAssignment.ExactLra(ExactLraAssignment(BooleanArray(0), listOf(third)))

        assertEquals("1/3", assignment.realValue(0))
        assertNull(assignment.algebraicRealValue(0))
    }
}
