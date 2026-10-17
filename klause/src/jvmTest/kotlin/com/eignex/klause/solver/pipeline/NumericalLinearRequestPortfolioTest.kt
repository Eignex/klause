package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.formats.mps.Mps
import com.eignex.klause.formats.mps.toExactSourceProblem
import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class NumericalLinearRequestPortfolioTest(private val engine: FiniteEngine, private val cores: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} cores={1}")
        fun configurations(): List<Array<Any>> = listOf(
            arrayOf(FiniteEngine.FIXED, 1),
            arrayOf(FiniteEngine.BACKTRACK, 1),
            arrayOf(FiniteEngine.BACKTRACK, 2),
            arrayOf(FiniteEngine.MIXED, 1),
            arrayOf(FiniteEngine.MIXED, 2),
        )
    }

    @Test
    fun `numerical satisfaction should retain its original source without an objective`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("i", true)), emptyList())
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.SATISFY, engine,
        ))

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(
            SourceExecutionRequest(invocation, ExecutionOptions(), cores = cores),
        ))

        assertSame(source, result.invocation.preparedPlan?.originalSource)
        assertNull(source.objective)
        assertEquals(listOf(BigFraction.ZERO), assertNotNull(result.linearSource?.incumbent).values)
        assertEquals(LinearSourceConclusion.SATISFIABLE, result.linearSource?.conclusion)
    }

    @Test
    fun `numerical execution should materialize the requested complete pool`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("i", true)), emptyList())
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.SATISFY, engine,
        ))

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(
            SourceExecutionRequest(invocation, ExecutionOptions(), cores = cores),
        ))

        assertEquals(if (engine == FiniteEngine.FIXED) 0 else cores, result.result.stats.portfolio.arms.size)
        assertEquals("numerical-mip", result.result.stats.run.backend)
    }

    @Test
    fun `numerical maximization should retain its source direction and attained bound`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("i", true, upper = SemanticRational.ofLong(2))),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE)),
                sense = ObjectiveSense.MAXIMIZE),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, engine, maximize = true,
        ))

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(
            SourceExecutionRequest(invocation, ExecutionOptions(), cores = cores),
        ))

        val optimum = assertIs<SourceClaim.Optimal>(assertNotNull(result.result.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ofLong(2)), optimum.value)
        assertEquals(ObjectiveSense.MAXIMIZE, result.result.scope.objective?.sense)
        assertEquals(BigFraction.ofLong(2), assertNotNull(result.linearSource?.incumbent).objective)
        assertSame(source, result.invocation.preparedPlan?.originalSource)
    }

    @Test
    fun `numerical cancellation should retain the shared allowance without a terminal`() {
        val source = LinearSourceProblem(listOf(LinearSourceVariable("i", true)), emptyList())
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.SATISFY, engine,
        ))
        var cancelled = false
        val budget = NodeBudget(3)
        val request = SourceExecutionRequest(invocation, ExecutionOptions(), cores = cores,
            nodeBudget = budget, cancellation = Cancellation { cancelled })
        cancelled = true

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(request))

        assertNull(result.result.terminal)
        assertNull(result.result.incumbent)
        assertEquals(TerminationReason.Cancelled, result.result.reason)
        assertEquals(0L, budget.spent)
    }

    @Test
    fun `unprojectable numerical rows should retain an incomplete result without arithmetic fallback`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n G R\nCOLUMNS\n X COST 1 R 1e-400\n" +
                "RHS\n RHS R 0.1\nBOUNDS\n UP B X 1\nENDATA\n",
        ).toExactSourceProblem()
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(compiled.source, ProblemSettings()), ExecutionOperation.OPTIMIZE, engine,
        ))

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(
            SourceExecutionRequest(invocation, ExecutionOptions(), cores = cores),
        ))

        assertNull(result.result.terminal)
        assertNull(result.result.incumbent)
        assertEquals(TerminationReason.Unsupported, result.result.reason)
        assertEquals(0L, result.result.stats.smt.sourceLp.operations)
        assertEquals(LinearSourceConclusion.UNKNOWN, result.linearSource?.conclusion)
    }


    @Test
    fun `numerical pools should retain integral source rays`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("i", true)), emptyList(),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(source, ProblemSettings()), ExecutionOperation.OPTIMIZE, engine,
        ))

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(
            SourceExecutionRequest(invocation, ExecutionOptions(), cores = cores),
        ))

        val ray = assertIs<SourceClaim.Ray>(assertNotNull(result.result.terminal).claim)
        assertEquals(0L, ray.sample.intValue(0))
        assertEquals(LinearSourceConclusion.UNBOUNDED, result.linearSource?.conclusion)
        assertSame(source, result.invocation.preparedPlan?.originalSource)
    }

    @Test
    fun `numerical pools should retain source fixed integer indicators`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n G R\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                " I COST 0\n J COST 0\n M1 'MARKER' 'INTEND'\n X COST 1 R 1\n" +
                "RHS\n RHS R 1\nBOUNDS\n FX B I 0\n FR B J\n LO B X 0\n" +
                "INDICATORS\n IF R I 0\nENDATA\n",
        ).toExactSourceProblem()
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(compiled.source, ProblemSettings()), ExecutionOperation.OPTIMIZE, engine,
        ))

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(
            SourceExecutionRequest(invocation, ExecutionOptions(), cores = cores),
        ))

        val optimum = assertIs<SourceClaim.Optimal>(assertNotNull(result.result.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ONE), optimum.value)
        assertEquals(0L, optimum.sample.intValue(0))
        assertEquals(BigFraction.ONE, assertNotNull(result.linearSource?.incumbent).objective)
    }


    @Test
    fun `numerical pools should withhold refutation when the shared decision allowance is exhausted`() {
        val compiled = Mps.parse(
            "ROWS\n N COST\n G R0\n G R1\nCOLUMNS\n M0 'MARKER' 'INTORG'\n" +
                " I COST 0\n J COST 0\n M1 'MARKER' 'INTEND'\n X COST 1 R0 1 R1 1\n" +
                "RHS\n RHS R0 1 R1 1\nBOUNDS\n BV B I\n FR B J\n FX B X 0\n" +
                "INDICATORS\n IF R0 I 0\n IF R1 I 1\nENDATA\n",
        ).toExactSourceProblem()
        val invocation = SourceSetup.prepare(SourceSetupRequest(
            SourceAttachment(compiled.source, ProblemSettings()), ExecutionOperation.OPTIMIZE, engine,
            boundCancellation = Cancellation { true },
        ))
        val budget = NodeBudget(0)

        val result = assertIs<SourceExecutionResult.Numerical>(SourceSetup.execute(
            SourceExecutionRequest(invocation, ExecutionOptions(), cores = cores, nodeBudget = budget),
        ))

        assertNull(result.result.terminal)
        assertNull(result.result.incumbent)
        assertEquals(TerminationReason.BudgetExhausted, result.result.reason)
        assertEquals(0L, budget.spent)
    }

}
