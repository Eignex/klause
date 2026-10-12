package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalFunction
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class TranscendentalTheoryRequestTest(private val engine: FiniteEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<Array<FiniteEngine>> = listOf(
            FiniteEngine.BACKTRACK, FiniteEngine.LOCAL_SEARCH, FiniteEngine.ALNS, FiniteEngine.MIXED,
        ).map { arrayOf(it) }
    }

    @Test
    fun `every strategy publishes an exact symbolic inverse witness`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val exp = TranscendentalExpression.Function(TranscendentalFunction.EXP, x)
        val source = TranscendentalProblem(
            TranscendentalFormula.Atom(exp, PolynomialRelation.EQ, constant(2)),
            emptyMap(), mapOf("x" to 0), emptyMap(),
        )
        val params = params()

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), params, engine)

        val result = assertIs<OpenTheoryResult.Sat>(execution.result)
        assertEquals("log(2)", result.assignment.realValue(0))
        assertIs<TranscendentalAssignment>(result.assignment)
        if (engine != FiniteEngine.BACKTRACK) assertTrue(result.stats.ls.moves.sum > 0)
    }

    @Test
    fun `only traversal can publish a whole source refutation`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val exp = TranscendentalExpression.Function(TranscendentalFunction.EXP, x)
        val source = TranscendentalProblem(
            TranscendentalFormula.Atom(exp, PolynomialRelation.LE, constant(0)),
            emptyMap(), mapOf("x" to 0), emptyMap(),
        )

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), params(), engine)

        if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) {
            assertIs<OpenTheoryResult.Unknown>(execution.result)
        } else assertIs<OpenTheoryResult.Unsat>(execution.result)
    }

    @Test
    fun `strict invocation bounds survive symbolic repairs`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val exp = TranscendentalExpression.Function(TranscendentalFunction.EXP, x)
        val source = TranscendentalProblem(
            TranscendentalFormula.Atom(exp, PolynomialRelation.EQ, constant(1)),
            emptyMap(), mapOf("x" to 0), emptyMap(),
        )
        val params = params().copy(realLower = mapOf(0 to BigFraction.ZERO), strictRealLower = setOf(0))

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), params, engine)

        assertTrue(execution.result !is OpenTheoryResult.Sat)
    }

    @Test
    fun `zero native allowance prevents source entry in every strategy`() {
        val source = TranscendentalProblem(TranscendentalFormula.Constant(true), emptyMap(), emptyMap(), emptyMap())

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), params().copy(maximumWork = 0), engine)

        assertIs<OpenTheoryResult.Unknown>(execution.result)
        assertEquals(0L, execution.workSpent)
    }

    @Test
    fun `strategy cancellation stops native work during an active completion`() {
        val x = TranscendentalExpression.Variable(SourceRealId(0))
        val source = TranscendentalProblem(
            TranscendentalFormula.Atom(TranscendentalExpression.Function(TranscendentalFunction.EXP, x),
                PolynomialRelation.EQ, constant(2)), emptyMap(), mapOf("x" to 0), emptyMap(),
        )
        var charged = 0L
        val meter = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val cancelled = Cancellation { charged >= 8 }
        val controls = params()
        val params = controls.copy(cancellation = meter, strategy = controls.strategy.copy(
            backtrack = controls.strategy.backtrack.copy(cancellation = cancelled),
            localSearch = controls.strategy.localSearch.copy(cancellation = cancelled),
        ))

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), params, engine)

        assertIs<OpenTheoryResult.Unknown>(execution.result)
        assertTrue(execution.workSpent in 1L..8L)
        assertEquals(execution.workSpent, charged)
    }

    private fun params(): TranscendentalTheoryParams = TranscendentalTheoryParams(
        maximumWork = 20000, maximumDepth = 0,
        strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 16, maxInstructions = 20000, randomSeed = 17),
            alnsIterations = 8,
        ),
    )

    private fun constant(value: Long): TranscendentalExpression.Constant {
        val fraction = BigFraction.ofLong(value)
        return TranscendentalExpression.Constant(PolynomialExpression.Constant.of(fraction.num, fraction.den))
    }
}
