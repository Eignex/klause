package com.eignex.klause.solver.pipeline

import com.eignex.klause.formats.smtlib.SmtLibAdmission
import com.eignex.klause.formats.smtlib.SmtLibInput
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
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
    fun `explicit cores reach the source dispatcher and fixed keeps its single worker`() {
        val source = assertIs<SmtLibInput.Transcendental>(SmtLibAdmission.parse("""
            (declare-const x Real)
            (assert (= (exp x) 2))
            (check-sat)
        """.trimIndent())).problem
        val params = PolynomialTheoryParams(maximumWork = 20000, strategy = PolynomialStrategyParams(
            localSearch = LocalSearchParams(maxFlips = 8, maxInstructions = 20000, randomSeed = 17),
            alnsIterations = 8))

        val execution = source.pipelineRequest().execute(params, engine, 4)

        val result = assertIs<OpenTheoryResult.Sat>(execution.result)
        assertEquals("log(2)", result.assignment.realValue(0))
        assertEquals(if (engine == FiniteEngine.FIXED) 0 else 4, result.stats.portfolio.arms.size)
        assertEquals("transcendental", execution.nativeWorkScope)
    }

    @Test
    fun `parallel invocation retains common pins and does not mutate the source`() {
        val source = assertIs<SmtLibInput.Transcendental>(SmtLibAdmission.parse("""
            (declare-const b Bool)
            (declare-const x Real)
            (assert b)
            (assert (= (exp x) 1))
            (check-sat)
        """.trimIndent())).problem
        val params = TranscendentalTheoryParams(maximumWork = 8000, maximumDepth = 0,
            strategy = PolynomialStrategyParams(assumptions = Assumptions.None.withBool(0, false),
                localSearch = LocalSearchParams(maxFlips = 16), alnsIterations = 16))

        val conditioned = OpenTheoryPipeline.execute(source.pipelineRequest(), params, engine, 4)
        val unrestricted = OpenTheoryPipeline.execute(source.pipelineRequest(), params.copy(
            strategy = params.strategy.copy(assumptions = Assumptions.None)), engine, 4)

        assertTrue(conditioned.result !is OpenTheoryResult.Sat)
        assertIs<OpenTheoryResult.Sat>(unrestricted.result)
    }

    @Test
    fun `parallel source repair respects strict invocation endpoints`() {
        val source = assertIs<SmtLibInput.Transcendental>(SmtLibAdmission.parse("""
            (declare-const x Real)
            (assert (= (exp x) 1))
            (check-sat)
        """.trimIndent())).problem
        val params = TranscendentalTheoryParams(maximumWork = 2000, maximumDepth = 0,
            realLower = mapOf(0 to BigFraction.ZERO), strictRealLower = setOf(0),
            strategy = PolynomialStrategyParams(localSearch = LocalSearchParams(maxFlips = 1), alnsIterations = 1))

        val result = OpenTheoryPipeline.execute(source.pipelineRequest(), params, engine, 4)

        assertTrue(result.result !is OpenTheoryResult.Sat)
    }

    @Test
    fun `cancelled parallel execution performs no native work`() {
        val source = assertIs<SmtLibInput.Transcendental>(SmtLibAdmission.parse("""
            (declare-const x Real)
            (assert (= (exp x) 2))
            (check-sat)
        """.trimIndent())).problem

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), TranscendentalTheoryParams(
            cancellation = Cancellation { true }), engine, 4)

        assertEquals(TerminationReason.Cancelled, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(0L, execution.workSpent)
    }

    @Test
    fun `parallel heuristics cannot turn failed neighborhoods into a source refutation`() {
        val source = assertIs<SmtLibInput.Transcendental>(SmtLibAdmission.parse("""
            (declare-const x Real)
            (assert (<= (exp x) 0))
            (check-sat)
        """.trimIndent())).problem
        val params = TranscendentalTheoryParams(maximumWork = 8000, maximumDepth = 0,
            strategy = PolynomialStrategyParams(localSearch = LocalSearchParams(maxFlips = 4), alnsIterations = 4))

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), params, engine, 4)

        if (engine == FiniteEngine.LOCAL_SEARCH || engine == FiniteEngine.ALNS) {
            assertIs<OpenTheoryResult.Unknown>(execution.result)
        } else assertIs<OpenTheoryResult.Unsat>(execution.result)
    }

    @Test
    fun `parallel workers cannot optimize a captured function objective`() {
        val source = assertIs<SmtLibInput.Transcendental>(SmtLibAdmission.parse("""
            (declare-const x Real)
            (assert (> (exp x) 0))
            (minimize (exp x))
            (check-sat)
        """.trimIndent())).problem

        val execution = OpenTheoryPipeline.execute(source.pipelineRequest(), TranscendentalTheoryParams(), engine, 4)

        assertEquals(TerminationReason.Unsupported, assertIs<OpenTheoryResult.Unknown>(execution.result).reason)
        assertEquals(0L, execution.workSpent)
    }
}
