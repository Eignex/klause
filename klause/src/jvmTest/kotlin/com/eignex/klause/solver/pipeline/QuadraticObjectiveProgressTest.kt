package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.solver.result.QuadraticOptimizationResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.util.numbers.parseBigInt
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class QuadraticObjectiveProgressTest(
    private val engine: FiniteEngine,
    private val sense: ObjectiveSense,
    private val openInteger: Boolean,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1} openInteger={2}")
        fun variants(): List<Array<Any>> = listOf(FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH).flatMap { engine ->
            ObjectiveSense.entries.flatMap { sense ->
                listOf(false, true).map { arrayOf<Any>(engine, sense, it) }
            }
        }

        @JvmStatic
        @BeforeClass
        fun initializeSolver() {
            OpenTheoryPipeline.minimize(
                Problem(
                    0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
                    realLower = doubleArrayOf(1.0, 2.0), realUpper = doubleArrayOf(2.0, 2.0),
                ).pipelineRequest(),
                QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(
                    QuadraticCoefficient.ONE, QuadraticVariable.Real(SourceRealId(0)),
                ))),
                engine = FiniteEngine.BACKTRACK,
            )
        }
    }

    @Test
    fun `quadratic progress should publish the nearest objective in source direction`() {
        val x = PolynomialExpression.Variable(SourceRealId(1))
        val formula = PolynomialRealFormula.And(listOf(
            PolynomialRealFormula.Atom(
                PolynomialExpression.Variable(SourceRealId(0)), PolynomialRelation.EQ,
                PolynomialExpression.Constant.of(parseBigInt("0")),
            ),
            PolynomialRealFormula.Atom(
                PolynomialExpression.Power(x, 2), PolynomialRelation.EQ,
                PolynomialExpression.Constant.of(parseBigInt("2")),
            ),
            PolynomialRealFormula.Atom(x, PolynomialRelation.GT, PolynomialExpression.Constant.of(parseBigInt("0"))),
        ))
        val coordinate = QuadraticVariable.Real(SourceRealId(0))
        val objective = QuadraticObjectiveSpec(listOf(QuadraticObjectiveTerm(QuadraticCoefficient.ONE, coordinate)))
        val events = mutableListOf<Double>()
        val listener: (SearchEvent) -> Unit = { if (it is SearchEvent.Incumbent) events += it.objective }
        val params = PolynomialTheoryParams(strategy = PolynomialStrategyParams(
            backtrack = BacktrackParams(onEvent = listener),
            localSearch = LocalSearchParams(maxFlips = 1, randomSeed = 17, onEvent = listener),
        ))

        val execution = if (openInteger) {
            OpenTheoryPipeline.minimize(
                NonlinearIntegerProblem(formula, 2, 0, mapOf("i" to 0), mapOf("x" to 1), emptyMap()).pipelineRequest(),
                objective, NonlinearIntegerTheoryParams(polynomial = params, maximumMoves = 1), engine, sense,
            )
        } else {
            OpenTheoryPipeline.minimize(
                Problem(
                    0, 0, emptyArray(), arrayOf(ContinuousProduct(0, 0, 1)), numRealVars = 2,
                    realLower = doubleArrayOf(1.0, 2.0), realUpper = doubleArrayOf(2.0, 2.0),
                ).pipelineRequest(),
                objective, params, engine, sense,
            )
        }

        val result = assertIs<QuadraticOptimizationResult.WithIncumbent>(execution.result)
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it == sqrt(2.0) }, events.toString())
        assertEquals(sqrt(2.0), result.stats.ls.incumbentObjective)
    }
}
