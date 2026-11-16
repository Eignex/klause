package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.AlgebraicReal
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.bigIntOf
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class NearestRealsTest {
    private fun sample(value: AlgebraicReal) =
        Sample(BooleanArray(0), LongArray(0), doubleArrayOf(value.guideDouble), algebraicReals = listOf(value))

    private fun root(constant: Long): AlgebraicReal =
        AlgebraicArithmetic().roots(RationalPolynomial.of(-constant, 0, 1))[1]

    @Test
    fun `narrowing should publish the nearest binary64 value of an algebraic coordinate`() {
        val narrowed = sample(root(2)).withNearestReals()

        assertEquals(sqrt(2.0), narrowed.approximateRealValue(0))
        assertEquals(emptyList<Int>(), narrowed.undeterminedReals())
    }

    @Test
    fun `a cancelled narrowing should publish the interval midpoint as undetermined`() {
        val value = root(1)

        val narrowed = sample(value).withNearestReals(ExecutionControl { true })

        assertEquals(value.approximateDouble, narrowed.approximateRealValue(0))
        assertEquals(listOf(0), narrowed.undeterminedReals())
    }

    @Test
    fun `an unsettled float point should not be published for acceptance`() {
        val published = sample(root(2)).publishedFloats(ExecutionControl { true }, PolynomialLimits())

        assertNull(published)
    }

    @Test
    fun `a finished nonlinear integer optimum should publish its nearest binary64 coordinate`() {
        val x = PolynomialExpression.Variable(SourceRealId(0))
        val two = PolynomialExpression.Constant.of(bigIntOf(2))
        val zero = PolynomialExpression.Constant.of(bigIntOf(0))
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.And(listOf(
                PolynomialRealFormula.Atom(PolynomialExpression.Product(listOf(x, x)), PolynomialRelation.EQ, two),
                PolynomialRealFormula.Atom(x, PolynomialRelation.GT, zero),
            )),
            2,
            0,
            mapOf("i" to 1),
            mapOf("x" to 0),
            emptyMap(),
        )
        val i = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, i, i),
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, QuadraticVariable.Real(SourceRealId(0))),
        ))

        val solved = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective, engine = FiniteEngine.BACKTRACK)

        val execution = solved.withNearestReals()

        val result = assertIs<QuadraticOptimizationResult.Optimal>(execution.result)
        assertEquals(sqrt(2.0), result.incumbent.sample.approximateRealValue(0))
    }
}
