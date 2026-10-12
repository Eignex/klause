package com.eignex.klause.solver.pipeline

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
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.BIG_ONE
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class QuadraticIntegerStrategiesTest(private val engine: FiniteEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<Array<FiniteEngine>> = listOf(
            FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED,
        ).map { arrayOf(it) }
    }

    @Test
    fun `wide source equality supplies exact checked heuristic centers`() {
        val wide = parseBigInt("100000000000000000000")
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Atom(PolynomialExpression.Variable(SourceRealId(0)), PolynomialRelation.EQ,
                PolynomialExpression.Constant.of(wide)),
            1, 0, mapOf("i" to 0), emptyMap(), emptyMap(),
        )
        val i = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, i, i)))
        val params = NonlinearIntegerTheoryParams(
            polynomial = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
                localSearch = LocalSearchParams(maxFlips = 1000, randomSeed = 17), alnsIterations = 2,
            )), maximumMoves = 1,
        )

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective, params, engine)

        val result = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result)
        assertEquals(wide, result.incumbent.sample.exactIntValue(0))
        assertEquals(
            BigFraction.of(parseBigInt("10000000000000000000000000000000000000000"), BIG_ONE),
            result.incumbent.value.rational,
        )
        if (engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.MIXED) {
            assertIs<QuadraticOptimizationResult.Optimal>(result)
        }
        else assertIs<QuadraticOptimizationResult.BestFound>(result)
        if (engine != FiniteEngine.BACKTRACK) assertTrue(result.stats.ls.moves.sum > 0)
        assertEquals(execution.polynomialStats.workSpent + execution.numericalWork, execution.nativeWork)
    }

    @Test
    fun `integer assumption survives every quadratic strategy`() {
        val source = NonlinearIntegerProblem(
            PolynomialRealFormula.Constant(true), 1, 0, mapOf("i" to 0), emptyMap(), emptyMap(),
        )
        val i = QuadraticVariable.Int(SourceIntId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, i, i)))
        val params = NonlinearIntegerTheoryParams(
            polynomial = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
                assumptions = Assumptions.None.withInt(0, 2),
                localSearch = LocalSearchParams(maxFlips = 1000, randomSeed = 17), alnsIterations = 2,
            )), maximumMoves = 2,
        )

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective, params, engine)

        val result = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result)
        assertEquals(parseBigInt("2"), result.incumbent.sample.exactIntValue(0))
        assertEquals(BigFraction.ofLong(4), result.incumbent.value.rational)
        if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) {
            assertIs<QuadraticOptimizationResult.BestFound>(result)
        }
    }

    @Test
    fun `strict continuous limit stays unattained after integer completion`() {
        val source = NonlinearIntegerProblem(PolynomialRealFormula.And(listOf(
            PolynomialRealFormula.Atom(PolynomialExpression.Variable(SourceRealId(0)), PolynomialRelation.EQ,
                PolynomialExpression.Constant.of(parseBigInt("1"))),
            PolynomialRealFormula.Atom(PolynomialExpression.Variable(SourceRealId(1)), PolynomialRelation.GT,
                PolynomialExpression.Constant.of(parseBigInt("0"))),
        )), 2, 0, mapOf("i" to 0), mapOf("x" to 1), emptyMap())
        val i = QuadraticVariable.Int(SourceIntId(0))
        val x = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, i, i),
            QuadraticObjectiveTerm(QuadraticCoefficient.ONE, x, x),
        ))
        val params = NonlinearIntegerTheoryParams(polynomial = PolynomialTheoryParams(
            strategy = PolynomialStrategyParams(
                localSearch = LocalSearchParams(maxFlips = 1000, randomSeed = 17), alnsIterations = 1,
            ),
        ), maximumMoves = 1)

        val execution = OpenTheoryPipeline.minimize(source.pipelineRequest(), objective, params, engine)

        val result = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result)
        assertEquals(parseBigInt("1"), result.incumbent.sample.exactIntValue(0))
        assertTrue(requireNotNull(result.incumbent.value.rational) > BigFraction.ONE)
        if (engine == FiniteEngine.BACKTRACK || engine == FiniteEngine.MIXED) {
            assertEquals(
                BigFraction.ONE, assertIs<QuadraticOptimizationResult.UnattainedLimit>(result).bound.value.rational,
            )
        } else assertIs<QuadraticOptimizationResult.BestFound>(result)
    }
}
