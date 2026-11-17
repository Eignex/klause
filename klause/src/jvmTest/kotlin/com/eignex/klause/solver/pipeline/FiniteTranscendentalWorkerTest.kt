package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalFunction
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.util.numbers.BigFraction
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@RunWith(Parameterized::class)
class FiniteTranscendentalWorkerTest(
    private val engine: FiniteEngine,
    private val function: String,
    private val scheduling: Boolean,
) {
    companion object {
        @BeforeClass
        @JvmStatic
        fun initializeRuntime() {
            val source = TranscendentalProblem(TranscendentalFormula.Atom(
                TranscendentalExpression.Function(TranscendentalFunction.EXP,
                    TranscendentalExpression.Variable(SourceRealId(1))), PolynomialRelation.EQ,
                TranscendentalExpression.Constant(PolynomialExpression.Constant.of(BigFraction.ONE.num))),
                mapOf("x" to 0), mapOf("y" to 1), emptyMap()).withFactors(Problem(
                0, 1, arrayOf(IntDomain(0, 0)), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0)))
            val params = TranscendentalTheoryParams(maximumWork = 10000,
                strategy = PolynomialStrategyParams(localSearch = LocalSearchParams(
                    maxFlips = 1, maxInstructions = 1000, randomSeed = 0), alnsIterations = 1))
            for (engine in FiniteEngine.entries) {
                val invocation = SourceSetup.prepare(SourceSetupRequest(
                    SourceAttachment(source, source.factorModel!!.settings), ExecutionOperation.SATISFY, engine))
                SourceSetup.execute(SourceExecutionRequest(invocation, params))
            }
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1} scheduling={2}")
        fun cases(): List<Array<Any>> = FiniteEngine.entries.flatMap { engine ->
            listOf("EXP", "LOG", "SIN", "COS").flatMap { function ->
                listOf(false, true).map { arrayOf(engine, function, it) }
            }
        }
    }

    @Test
    fun `native globals and function completion should agree on each discrete coordinate`() {
        val function = TranscendentalFunction.valueOf(function)
        val input = if (function == TranscendentalFunction.LOG) BigFraction.ONE else BigFraction.ZERO
        val expected = if (function in listOf(TranscendentalFunction.EXP, TranscendentalFunction.COS)) 1L else 0L
        val global = if (scheduling) Cumulative(intArrayOf(0, 1), longArrayOf(1, 1), longArrayOf(1, 1), 1)
        else AllDifferent(intArrayOf(0, 1), 0, 2)
        val factors = Problem(0, 2, arrayOf(IntDomain(0, 1), IntDomain(0, 1)), arrayOf(global,
            ExactLinear.of(intArrayOf(Term.ofRealVar(0)), listOf(BigFraction.ONE), LinearOp.EQ, input)),
            numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val functions = TranscendentalProblem(TranscendentalFormula.Atom(
            TranscendentalExpression.Function(function, TranscendentalExpression.Variable(SourceRealId(0))),
            PolynomialRelation.EQ, TranscendentalExpression.Variable(SourceRealId(1))),
            linkedMapOf("x" to 1, "z" to 2), mapOf("y" to 0), emptyMap()).withFactors(factors)
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(functions, factors.settings),
            ExecutionOperation.SATISFY, engine))
        val plan = assertNotNull(invocation.preparedPlan)
        val model = assertNotNull(assertIs<PreparedExecution.Transcendental>(plan.execution).completion)
        assertSame(global, model.finite.factors.single())
        assertEquals(0, plan.finiteIntegerCoordinate(0))
        assertEquals(0, plan.integerCoordinate(0))
        assertEquals(0, plan.realCoordinate(0))
        val params = TranscendentalTheoryParams(maximumWork = 30000, maximumDepth = 0,
            strategy = PolynomialStrategyParams(localSearch = LocalSearchParams(
                maxFlips = 32, maxInstructions = 10000, randomSeed = 0), alnsIterations = 4))

        val execution = assertIs<SourceExecutionResult.Transcendental>(
            SourceSetup.execute(SourceExecutionRequest(invocation, params)))

        val point = assertIs<OpenTheoryResult.Sat>(execution.result.result).assignment
        assertEquals(expected.toString(), point.intValue(0))
        assertEquals((1L - expected).toString(), point.intValue(1))
        assertEquals(input.toString(), point.realValue(0))
    }
}
