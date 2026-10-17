package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class TranscendentalPortfolioInvocationTest(private val engine: FiniteEngine) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun engines(): List<Array<FiniteEngine>> = FiniteEngine.entries.map { arrayOf(it) }
    }

    @Test
    fun `explicit cores should reach the source dispatcher while fixed keeps a single worker`() {
        val source = assertIs<TranscendentalProblem>((
            SmtLib.parse(
                """
            (declare-const x Real)
            (assert (= (exp x) 2))
            (check-sat)
                """.trimIndent(),
            )).source.originalSource)
        val params = PolynomialTheoryParams(
            maximumWork = 100000,
            strategy = PolynomialStrategyParams(
                localSearch = LocalSearchParams(maxFlips = 16, maxInstructions = 100000, randomSeed = 17),
                alnsIterations = 16,
            ),
        )

        val execution = source.pipelineRequest().execute(params, engine, 4)

        val result = assertIs<OpenTheoryResult.Sat>(execution.result)
        assertEquals("log(2)", result.assignment.realValue(0))
        assertEquals(if (engine == FiniteEngine.FIXED) 0 else 4, result.stats.portfolio.arms.size)
        assertEquals("transcendental", execution.nativeWorkScope)
    }

    @Test
    fun `parallel invocation should keep common pins without mutating the source`() {
        val source = assertIs<TranscendentalProblem>((
            SmtLib.parse(
                """
            (declare-const b Bool)
            (declare-const x Real)
            (assert b)
            (assert (= (exp x) 1))
            (check-sat)
                """.trimIndent(),
            )).source.originalSource)
        val params = TranscendentalTheoryParams(
            maximumWork = 100000,
            maximumDepth = 0,
            strategy = PolynomialStrategyParams(
                assumptions = Assumptions.None.withBool(0, false),
                localSearch = LocalSearchParams(maxFlips = 16),
                alnsIterations = 16,
            ),
        )

        val conditioned = OpenTheoryPipeline.execute(source.pipelineRequest(), params, engine, 4)
        val unrestricted = OpenTheoryPipeline.execute(
            source.pipelineRequest(),
            params.copy(
                strategy = params.strategy.copy(assumptions = Assumptions.None),
            ),
            engine,
            4,
        )

        assertTrue(conditioned.result !is OpenTheoryResult.Sat)
        assertIs<OpenTheoryResult.Sat>(unrestricted.result)
    }

    @Test
    fun `parallel workers should not optimize a captured function objective`() {
        val source = assertIs<TranscendentalProblem>((
            SmtLib.parse(
                """
            (declare-const x Real)
            (assert (> (exp x) 0))
            (minimize (exp x))
            (check-sat)
                """.trimIndent(),
            )).source.originalSource)

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), TranscendentalTheoryParams(), engine, 4)

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(0L, execution.workSpent)
    }
}
