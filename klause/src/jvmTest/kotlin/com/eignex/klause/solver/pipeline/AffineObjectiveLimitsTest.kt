package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.formats.smtlib.SmtLibPolynomial
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialRealProblem
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class AffineObjectiveLimitsTest(private val sense: ObjectiveSense, private val affine: Boolean) {
    private val coefficient = if (sense == ObjectiveSense.MINIMIZE) 1L else -1L
    private val linear = LinearObjective(constant = 7, realCoefficients = doubleArrayOf(coefficient.toDouble()))
    private val quadratic = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(
        QuadraticCoefficient.ofLong(coefficient), QuadraticVariable.Real(SourceRealId(0)),
    )), QuadraticCoefficient.ofLong(7))

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} affine={1}")
        fun variants(): List<Array<Any>> = ObjectiveSense.entries.flatMap { sense ->
            listOf(true, false).map { arrayOf<Any>(sense, it) }
        }
    }

    @Test
    fun `strict affine limit stays unattained in source direction`() {
        val parsed = SmtLibPolynomial.parse("(declare-const x Real) (assert (> x 0)) (check-sat)")
        val request = PolynomialRealProblem(parsed.formula, parsed.realCount, parsed.boolCount,
            parsed.sourceRealCount, parsed.sourceBoolCount, parsed.realVarNames, parsed.boolVarNames).pipelineRequest()

        val result = if (affine) OpenTheoryPipeline.optimize(request, linear, engine = FiniteEngine.BACKTRACK,
            sense = sense).result else OpenTheoryPipeline.minimize(request, quadratic,
            engine = FiniteEngine.BACKTRACK, sense = sense).result

        val limit = assertIs<QuadraticOptimizationResult.UnattainedLimit>(result)
        val arithmetic = AlgebraicArithmetic()
        val x = requireNotNull(limit.incumbent.sample.algebraicReals)[0]
        assertTrue(arithmetic.sign(x) > 0)
        assertEquals(BigFraction.ofLong(7), limit.bound.value.rational)
        assertTrue(limit.bound.strict)
        assertEquals(sense, limit.sense)
        val gap = arithmetic.compare(limit.incumbent.value, limit.bound.value)
        assertEquals(if (sense == ObjectiveSense.MINIMIZE) 1 else -1, gap)
    }

    @Test
    fun `affine unboundedness retains a feasible point and original direction`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))

        val result = if (affine) OpenTheoryPipeline.optimize(source.pipelineRequest(), linear,
            engine = FiniteEngine.BACKTRACK, sense = sense).result else OpenTheoryPipeline.minimize(
            source.pipelineRequest(), quadratic, engine = FiniteEngine.BACKTRACK, sense = sense).result

        val unbounded = assertIs<QuadraticOptimizationResult.Unbounded>(result)
        assertEquals(sense, unbounded.sense)
        val x = requireNotNull(unbounded.incumbent.sample.algebraicReals)[0]
        val arithmetic = AlgebraicArithmetic()
        val expected = arithmetic.add(arithmetic.rational(BigFraction.ofLong(7)),
            arithmetic.multiply(arithmetic.rational(BigFraction.ofLong(coefficient)), x))
        assertEquals(0, arithmetic.compare(expected, unbounded.incumbent.value))
    }

    @Test
    fun `affine infeasibility does not manufacture an incumbent`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(1.0), realUpper = doubleArrayOf(0.0))

        val result = if (affine) OpenTheoryPipeline.optimize(source.pipelineRequest(), linear,
            engine = FiniteEngine.BACKTRACK, sense = sense).result else OpenTheoryPipeline.minimize(
            source.pipelineRequest(), quadratic, engine = FiniteEngine.BACKTRACK, sense = sense).result

        assertEquals(sense, assertIs<QuadraticOptimizationResult.Infeasible>(result).sense)
    }

    @Test
    fun `affine objective values keep exact algebraic authority`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(0.0, 2.0), realUpper = doubleArrayOf(2.0, 2.0))

        val result = if (affine) OpenTheoryPipeline.optimize(source.pipelineRequest(), linear,
            engine = FiniteEngine.BACKTRACK, sense = sense).result else OpenTheoryPipeline.minimize(
            source.pipelineRequest(), quadratic, engine = FiniteEngine.BACKTRACK, sense = sense).result

        val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result)
        val arithmetic = AlgebraicArithmetic()
        val x = requireNotNull(optimum.incumbent.sample.algebraicReals)[0]
        assertEquals(0, arithmetic.signAt(RationalPolynomial.of(-2, 0, 1), x))
        assertTrue(arithmetic.sign(x) > 0)
        val expected = arithmetic.add(arithmetic.rational(BigFraction.ofLong(7)),
            arithmetic.multiply(arithmetic.rational(BigFraction.ofLong(coefficient)), x))
        assertEquals(0, arithmetic.compare(expected, optimum.incumbent.value))
        assertEquals(0, arithmetic.compare(expected, optimum.bound.value))
        assertEquals(sense, optimum.sense)
    }
}
