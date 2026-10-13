package com.eignex.klause.solver.pipeline

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.localsearch.LocalSearchParams
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
class AffineOptimizationExecutionTest(
    private val engine: FiniteEngine,
    private val sense: ObjectiveSense,
    private val affine: Boolean,
    private val constant: Long,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1} affine={2} constant={3}")
        fun variants(): List<Array<Any>> = FiniteEngine.entries.flatMap { engine ->
            ObjectiveSense.entries.flatMap { sense ->
                listOf(true, false).flatMap { affine ->
                    listOf(7L, Long.MAX_VALUE).map { arrayOf<Any>(engine, sense, affine, it) }
                }
            }
        }
    }

    @Test
    fun `affine and quadratic encodings optimize the same shared polynomial source`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(1.0, Double.POSITIVE_INFINITY))
        val expected = BigFraction.ofLong(constant) +
            if (sense == ObjectiveSense.MINIMIZE) BigFraction.ZERO else BigFraction.ONE
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17), alnsIterations = 1,
        ))

        val execution = if (affine) OpenTheoryPipeline.optimize(
            source.pipelineRequest(),
            LinearObjective(constant = constant, realCoefficients = doubleArrayOf(0.0, 1.0)),
            params, engine, sense,
        ) else OpenTheoryPipeline.minimize(
            source.pipelineRequest(), QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(
                QuadraticCoefficient.ONE, QuadraticVariable.Real(SourceRealId(1)),
            )), QuadraticCoefficient.ofLong(constant)), params, engine, sense,
        )

        val result = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result)
        val sample = result.incumbent.sample
        val arithmetic = AlgebraicArithmetic()
        val values = requireNotNull(sample.algebraicReals)
        assertEquals(0, arithmetic.compare(arithmetic.multiply(values[0], values[0]), values[1]))
        assertEquals(expected, result.incumbent.value.rational)
        assertEquals(sense, result.sense)
        if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) {
            assertIs<QuadraticOptimizationResult.BestFound>(result)
        } else {
            val optimum = assertIs<QuadraticOptimizationResult.Optimal>(result)
            assertEquals(expected, optimum.bound.value.rational)
        }
        if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) {
            assertTrue(result.stats.ls.moves.sum > 0)
        }
        if (engine == FiniteEngine.MIXED) assertEquals(3, result.stats.portfolio.arms.size)
        assertTrue(execution.nativeWork > 0)
    }

    @Test
    fun `accepted callbacks preserve exact source values for both objective encodings`() {
        val source = Problem(0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
            realLower = doubleArrayOf(-1.0, Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(1.0, Double.POSITIVE_INFINITY))
        val expected = BigFraction.ofLong(constant) +
            if (sense == ObjectiveSense.MINIMIZE) BigFraction.ZERO else BigFraction.ONE
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17), alnsIterations = 1,
        ))
        val published = mutableListOf<QuadraticIncumbentPublication>()

        val execution = if (affine) OpenTheoryPipeline.optimize(
            source.pipelineRequest(),
            LinearObjective(constant = constant, realCoefficients = doubleArrayOf(0.0, 1.0)),
            params, engine, sense, 1, onAccepted = { published += it },
        ) else OpenTheoryPipeline.minimize(
            source.pipelineRequest(), QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(
                QuadraticCoefficient.ONE, QuadraticVariable.Real(SourceRealId(1)),
            )), QuadraticCoefficient.ofLong(constant)), params, engine, sense, 1,
            onAccepted = { published += it },
        )

        assertTrue(published.isNotEmpty())
        val arithmetic = AlgebraicArithmetic()
        for (publication in published) {
            val values = requireNotNull(publication.incumbent.sample.algebraicReals)
            assertTrue(arithmetic.compare(values[0], arithmetic.rational(BigFraction.ofLong(-1))) >= 0)
            assertTrue(arithmetic.compare(values[0], arithmetic.rational(BigFraction.ONE)) <= 0)
            assertEquals(0, arithmetic.compare(arithmetic.multiply(values[0], values[0]), values[1]))
            val originalValue = arithmetic.add(arithmetic.rational(BigFraction.ofLong(constant)), values[1])
            assertEquals(0, arithmetic.compare(originalValue, publication.incumbent.value))
        }
        assertEquals(expected, published.last().incumbent.value.rational)
    }
}
