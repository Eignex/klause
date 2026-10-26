package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.Polynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolynomialFailureCaptureTest {
    @Test
    fun `completed observation preserves a strict irrational witness`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (and (= (* x x) 2) (> x 0)))")
        val plain = solveQfNra(source.formula, source.boolCount, source.realCount)
        val capture = PolynomialFailureCapture()
        val query = QfNraQuery(source.formula, source.boolCount, source.realCount, QfNraParams(), PolynomialCondition())
        query.failureCapture = capture

        var observed: QfNraResult? = null
        while (observed == null) observed = query.next()

        val expected = assertIs<QfNraResult.Satisfiable>(plain).assignment.realValue(0)
        val actual = assertIs<QfNraResult.Satisfiable>(observed).assignment.realValue(0)
        assertEquals(0, AlgebraicArithmetic().compare(expected, actual))
        assertTrue(observed.stats.workSpent >= plain.stats.workSpent)
        assertEquals("true", capture.scope["complete"])
    }

    @Test
    fun `capture retains negative premises and exact condition bounds`() {
        val arithmetic = PolynomialArithmetic()
        val capture = PolynomialFailureCapture()
        val atom = PolynomialAtom(Polynomial.variable(0), PolynomialRelation.LT)
        val formula = PolynomialFormula.conjunction(listOf(atom), arithmetic)
        capture.scope(formula, PolynomialCondition(lower = mapOf(0 to BigFraction.MINUS_ONE)), arithmetic)

        capture.rejected(formula, PolynomialBooleanRegion(mapOf(0 to false), emptyMap()),
            mapOf(0 to AlgebraicArithmetic().rational(BigFraction.MINUS_ONE)), 0, 3, 17, arithmetic)

        val failure = assertNotNull(capture.firstCadFailure)
        assertEquals("false", failure["premise.0"])
        assertEquals("3", failure["candidate.snapshot"])
        assertEquals("-1", capture.scope["condition.lower.0"])
        assertEquals("LT", failure["active.0.relation"])
        assertEquals("17", failure["work"])
    }

    @Test
    fun `repetition observation stops at its comparison allowance`() {
        val arithmetic = PolynomialArithmetic()
        val capture = PolynomialFailureCapture()
        val formula = PolynomialFormula.conjunction(emptyList(), arithmetic)
        val point = mapOf(0 to AlgebraicArithmetic().rational(BigFraction.ZERO))

        repeat(300) { capture.rejected(formula, null, point, null, 1, it.toLong(), arithmetic) }

        assertEquals(300L, capture.failures)
        assertEquals(255L, capture.repeatedFirstFailure)
        assertEquals(false, capture.repetitionComplete)
    }
}
