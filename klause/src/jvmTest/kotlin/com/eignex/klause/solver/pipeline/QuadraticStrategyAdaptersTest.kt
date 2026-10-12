package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class QuadraticStrategyAdaptersTest(private val engine: FiniteEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<Array<FiniteEngine>> = listOf(
            FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED,
        ).map { arrayOf(it) }
    }

    @Test
    fun `mixed quadratic terms optimize the source integers and exact real completions`() {
        val source = Problem(
            0, 1, arrayOf(IntDomain(-1, 2)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-3.0), realUpper = doubleArrayOf(3.0),
        )
        val i = QuadraticVariable.Int(SourceIntId(0))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x),
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-2), i, x),
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(2), i, i),
            QuadraticObjectiveTerm(QuadraticCoefficient.ofLong(-2), i),
        ), QuadraticCoefficient.ONE)
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 10000, randomSeed = 17), alnsIterations = 2,
        ))

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective, params, engine)

        val result = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result)
        val integer = BigFraction.ofLong(result.incumbent.sample.intValue(0))
        val real = requireNotNull(result.incumbent.sample.algebraicReals?.get(0)?.rational)
        val expected = (real - integer) * (real - integer) +
            (integer - BigFraction.ONE) * (integer - BigFraction.ONE)
        assertEquals(expected, result.incumbent.value.rational)
        if (engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.MIXED) {
            assertEquals(1L, result.incumbent.sample.intValue(0))
            assertEquals(BigFraction.ZERO, assertIs<QuadraticOptimizationResult.Optimal>(result).bound.value.rational)
        } else assertIs<QuadraticOptimizationResult.BestFound>(result)
        if (engine != FiniteEngine.BACKTRACK) assertTrue(result.stats.ls.moves.sum > 0)
        assertTrue(execution.nativeWork > 0)
    }

    @Test
    fun `quadratic workers enforce the same captured source assumption`() {
        val source = Problem(
            0, 1, arrayOf(IntDomain(1, 2)), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(-1.0), realUpper = doubleArrayOf(1.0),
        )
        val i = QuadraticVariable.Int(SourceIntId(0))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, i, i),
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x),
        ))
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            assumptions = Assumptions.None.withInt(0, 2),
            localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17), alnsIterations = 1,
        ))

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective, params, engine)

        val result = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result)
        assertEquals(2L, result.incumbent.sample.intValue(0))
        assertEquals(BigFraction.ofLong(4), result.incumbent.value.rational)
    }

    @Test
    fun `quadratic optimization retains finite global constraint semantics`() {
        val source = Problem(
            0, 2, arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf(
                AllDifferent(intArrayOf(0, 1), 0, 3),
                Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), 1),
            ),
        )
        val first = QuadraticVariable.Int(SourceIntId(0))
        val second = QuadraticVariable.Int(SourceIntId(1))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, first, first),
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, second, second),
        ))
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 1000, randomSeed = 17), alnsIterations = 2,
        ))

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective, params, engine)

        val result = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result)
        val left = result.incumbent.sample.intValue(0)
        val right = result.incumbent.sample.intValue(1)
        assertTrue(left != right)
        assertEquals(BigFraction.ofLong(left * left + right * right), result.incumbent.value.rational)
        if (engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.MIXED) {
            assertEquals(BigFraction.ONE, assertIs<QuadraticOptimizationResult.Optimal>(result).bound.value.rational)
        } else assertIs<QuadraticOptimizationResult.BestFound>(result)
    }
}
