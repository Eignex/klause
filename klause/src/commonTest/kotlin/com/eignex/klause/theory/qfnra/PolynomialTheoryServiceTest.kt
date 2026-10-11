package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class PolynomialTheoryServiceTest {
    @Test
    fun `conditioned queries isolate finite pins`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const x Real)
            (declare-const k Real)
            (assert (= (* x x) k))
        """.trimIndent())
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            val negative = PolynomialCondition(lower = mapOf(1 to BigFraction.MINUS_ONE),
                upper = mapOf(1 to BigFraction.MINUS_ONE))
            val positive = PolynomialCondition(lower = mapOf(1 to BigFraction.ofLong(2)),
                upper = mapOf(1 to BigFraction.ofLong(2)))
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(negative))
            val witness = assertIs<QfNraResult.Satisfiable>(invocation.check(positive)).assignment
            assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), witness.realValue(0)))
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(negative))
        }
    }

    @Test
    fun `Boolean query conditions preserve correlation`() {
        val source = SmtLibPolynomial.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (assert (or b (= (* x x) (- 1))))
        """.trimIndent())
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            assertIs<QfNraResult.Unsatisfiable>(invocation.check(PolynomialCondition(bools = mapOf(0 to false))))
            val witness = assertIs<QfNraResult.Satisfiable>(
                invocation.check(PolynomialCondition(bools = mapOf(0 to true))),
            ).assignment
            assertEquals(true, witness.boolValue(0))
        }
    }

    @Test
    fun `native allowance persists between queries`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val required = service.invocation().use { it.check().stats.workSpent }

        service.invocation(QfNraParams(maximumWork = required + 1)).use { invocation ->
            assertIs<QfNraResult.Satisfiable>(invocation.check())
            val stopped = assertIs<QfNraResult.Unknown>(invocation.check())
            assertEquals(QfNraReason.WorkLimit, stopped.reason)
        }
    }

    @Test
    fun `native work reaches caller meter once`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        var charged = 0L
        val token = object : Cancellation, WorkMeter {
            override fun isCancelled() = false
            override fun workMeter(): WorkMeter = this
            override fun charge(units: Long) { charged += units }
        }

        service.invocation(QfNraParams(cancellation = token)).use { invocation ->
            invocation.check()
            invocation.check()
            assertEquals(invocation.stats.workSpent, charged)
        }
    }

    @Test
    fun `closing an invocation preserves published coordinates`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= (* x x) 2))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)
        val invocation = service.invocation()
        val witness = assertIs<QfNraResult.Satisfiable>(invocation.check()).assignment

        invocation.close()
        invocation.close()
        assertFailsWith<IllegalStateException> { invocation.check() }
        assertEquals(0, AlgebraicArithmetic().signAt(RationalPolynomial.of(-2, 0, 1), witness.realValue(0)))
    }

    @Test
    fun `cancelled query does not contaminate a fresh invocation`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (= x 1))")
        val service = PolynomialTheoryService(source.formula, source.boolCount, source.realCount)

        service.invocation().use { invocation ->
            assertEquals(QfNraReason.Interrupted,
                assertIs<QfNraResult.Unknown>(invocation.check(cancellation = Cancellation { true })).reason)
        }
        service.invocation().use { assertIs<QfNraResult.Satisfiable>(it.check()) }
    }
}
