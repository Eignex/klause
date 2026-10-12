package com.eignex.klause.theory.qfnra

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PolynomialObjectiveImageTest {
    @Test
    fun `square objective attains its global continuous minimum`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (check-sat)")
        val objective = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2)

        val result = solvePolynomialObjectiveImage(source.formula, objective, source.boolCount, source.realCount)

        val optimum = assertIs<PolynomialObjectiveResult.Optimum>(result)
        assertEquals(BigFraction.ZERO, optimum.value.rational)
        assertEquals(BigFraction.ZERO, optimum.assignment.realValue(0).rational)
    }

    @Test
    fun `strict square boundary has an unattained infimum`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0)) (check-sat)")
        val objective = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2)

        val result = solvePolynomialObjectiveImage(source.formula, objective, source.boolCount, source.realCount)

        assertEquals(BigFraction.ZERO, assertIs<PolynomialObjectiveResult.Infimum>(result).value.rational)
    }

    @Test
    fun `a disjunctive feasible minimizer establishes attainment`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (or (> x 1) (= x 0))) (check-sat)",
        )
        val objective = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2)

        val result = solvePolynomialObjectiveImage(source.formula, objective, source.boolCount, source.realCount)

        assertEquals(BigFraction.ZERO, assertIs<PolynomialObjectiveResult.Optimum>(result).value.rational)
    }

    @Test
    fun `an objective image unbounded below establishes unboundedness`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (check-sat)")
        val objective = PolynomialExpression.Product(listOf(
            PolynomialExpression.Constant.of(BigFraction.MINUS_ONE.num),
            PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2),
        ))

        val result = solvePolynomialObjectiveImage(source.formula, objective, source.boolCount, source.realCount)

        assertIs<PolynomialObjectiveResult.Unbounded>(result)
    }

    @Test
    fun `empty source objective image establishes infeasibility`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (assert (< (* x x) 0)) (check-sat)")
        val objective = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2)

        val result = solvePolynomialObjectiveImage(source.formula, objective, source.boolCount, source.realCount)

        assertIs<PolynomialObjectiveResult.Infeasible>(result)
    }

    @Test
    fun `irrational objective endpoint retains exact scalar identity`() {
        val source = SmtLibPolynomial.parse(
            "(declare-const x Real) (assert (and (= (* x x) 2) (> x 0))) (check-sat)",
        )
        val objective = PolynomialExpression.Variable(SourceRealId(0))

        val result = solvePolynomialObjectiveImage(source.formula, objective, source.boolCount, source.realCount)

        val optimum = assertIs<PolynomialObjectiveResult.Optimum>(result)
        assertEquals(0, AlgebraicArithmetic().compare(optimum.value, optimum.assignment.realValue(0)))
    }

    @Test
    fun `exhausted objective image work cannot establish global optimality`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (check-sat)")
        val objective = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2)

        val result = solvePolynomialObjectiveImage(
            source.formula, objective, source.boolCount, source.realCount, QfNraParams(maximumWork = 0),
        )

        assertEquals(QfNraReason.WorkLimit, assertIs<PolynomialObjectiveResult.Unknown>(result).reason)
    }

    @Test
    fun `cancelled objective image scope publishes no certificate`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (check-sat)")
        val objective = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2)

        val result = solvePolynomialObjectiveImage(
            source.formula, objective, source.boolCount, source.realCount,
            QfNraParams(cancellation = Cancellation { true }),
        )

        assertEquals(QfNraReason.Interrupted, assertIs<PolynomialObjectiveResult.Unknown>(result).reason)
    }

    @Test
    fun `native image work reaches its containing meter once`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (check-sat)")
        val objective = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2)
        var work = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { work += it }
        }

        val result = solvePolynomialObjectiveImage(
            source.formula, objective, source.boolCount, source.realCount, QfNraParams(cancellation),
        )

        assertEquals(work, assertIs<PolynomialObjectiveResult.Optimum>(result).stats.workSpent)
    }

    @Test
    fun `exhaustion before final image publication discards the global verdict`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (check-sat)")
        val objective = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2)
        val completed = solvePolynomialObjectiveImage(source.formula, objective, source.boolCount, source.realCount)

        val result = solvePolynomialObjectiveImage(
            source.formula, objective, source.boolCount, source.realCount,
            QfNraParams(maximumWork = completed.stats.workSpent - 1),
        )

        assertEquals(QfNraReason.WorkLimit, assertIs<PolynomialObjectiveResult.Unknown>(result).reason)
    }

    @Test
    fun `conditioned optimization cannot mutate the retained source scope`() {
        val source = SmtLibPolynomial.parse("(declare-const x Real) (check-sat)")
        val objective = PolynomialExpression.Power(PolynomialExpression.Variable(SourceRealId(0)), 2)
        solvePolynomialObjectiveImage(
            source.formula, objective, source.boolCount, source.realCount,
            condition = PolynomialCondition(lower = mapOf(0 to BigFraction.ofLong(2))),
        )

        val result = solvePolynomialObjectiveImage(source.formula, objective, source.boolCount, source.realCount)

        assertEquals(BigFraction.ZERO, assertIs<PolynomialObjectiveResult.Optimum>(result).value.rational)
    }
}
