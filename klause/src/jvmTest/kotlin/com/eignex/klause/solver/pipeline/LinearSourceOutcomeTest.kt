package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LinearSourceOutcomeTest(private val exact: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "exact={0}")
        fun policies(): List<Boolean> = listOf(false, true)
    }

    private val tolerance = if (exact) SourceTolerance() else SourceTolerance(
        absolute = SemanticRational.of(BIG_ONE, bigIntOf(10_000_000)),
    )

    @Test
    fun `a checked terminal optimum should retain its source units under the selected policy`() {
        val tenth = BigFraction.ofLong(10).reciprocal()
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ONE, SemanticRational.ofLong(2))),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0,
                SemanticRational.of(tenth.num, tenth.den)))),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance).resolve(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED))
        val cost = if (exact) tenth else assertNotNull(BigFraction.ofDouble(0.1))
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0, 0.1),
            exactReals = listOf(BigFraction.ONE, cost))
        val native = OpenTheoryExecution.Optimize(OpenTheoryOptimum.Optimal(
            OpenTheoryAssignment.Sampled(point), cost, SolveStats.EMPTY))

        val result = executeLinearSource(SourceExecutionRequest(invocation), SourceExecutionCallbacks()) { _, _, _ ->
            SourceExecutionResult.Linear(invocation, native)
        }

        val outcome = assertNotNull(result.linearSource)
        assertEquals(LinearSourceConclusion.OPTIMAL, outcome.conclusion)
        assertEquals(tenth, outcome.incumbent?.objective)
        assertEquals(!exact, outcome.numericalClaims)
    }

    @Test
    fun `a rejected terminal optimum should retain only an earlier accepted incumbent`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ONE, SemanticRational.ofLong(2))),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance).resolve(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED))
        val rejected = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0, 0.0),
            exactReals = listOf(BigFraction.ZERO, BigFraction.ZERO))
        val native = OpenTheoryExecution.Optimize(OpenTheoryOptimum.Optimal(
            OpenTheoryAssignment.Sampled(rejected), BigFraction.ZERO, SolveStats.EMPTY))

        val result = executeLinearSource(SourceExecutionRequest(invocation), SourceExecutionCallbacks()) { _, hooks, _ ->
            hooks.finite.onSample(Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(2.0, 2.0),
                exactReals = listOf(BigFraction.ofLong(2), BigFraction.ofLong(2))))
            SourceExecutionResult.Linear(invocation, native)
        }

        val outcome = assertNotNull(result.linearSource)
        assertEquals(LinearSourceConclusion.SATISFIABLE, outcome.conclusion)
        assertEquals(BigFraction.ofLong(2), outcome.incumbent?.objective)
    }

    @Test
    fun `an inconsistent terminal cost should withhold source optimality under either policy`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ONE, SemanticRational.ofLong(2))),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance).resolve(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED))
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0, 0.0),
            exactReals = listOf(BigFraction.ONE, BigFraction.ZERO))
        val native = OpenTheoryExecution.Optimize(OpenTheoryOptimum.Optimal(
            OpenTheoryAssignment.Sampled(point), BigFraction.ZERO, SolveStats.EMPTY))

        val result = executeLinearSource(SourceExecutionRequest(invocation), SourceExecutionCallbacks()) { _, _, _ ->
            SourceExecutionResult.Linear(invocation, native)
        }

        assertEquals(LinearSourceConclusion.SATISFIABLE, assertNotNull(result.linearSource).conclusion)
    }

    @Test
    fun `a source better incumbent should retain numerical optimality at a tied native cost`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ONE, SemanticRational.ofLong(2))),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance).resolve(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED))
        val value = BigFraction.ONE + BigFraction.ofLong(100_000_000).reciprocal()
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(value.toDouble(), 1.0),
            exactReals = listOf(value, BigFraction.ONE))
        val native = OpenTheoryExecution.Optimize(OpenTheoryOptimum.Optimal(
            OpenTheoryAssignment.Sampled(point), BigFraction.ONE, SolveStats.EMPTY))

        val result = executeLinearSource(SourceExecutionRequest(invocation), SourceExecutionCallbacks()) { _, hooks, _ ->
            hooks.finite.onSample(Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0, 1.0),
                exactReals = listOf(BigFraction.ONE, BigFraction.ONE)))
            SourceExecutionResult.Linear(invocation, native)
        }

        val outcome = assertNotNull(result.linearSource)
        val expected = if (exact) LinearSourceConclusion.SATISFIABLE else LinearSourceConclusion.OPTIMAL
        assertEquals(expected, outcome.conclusion)
        assertEquals(BigFraction.ONE, outcome.incumbent?.objective)
    }

    @Test
    fun `a restricted native refutation should withhold an original source refutation`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ZERO, SemanticRational.ONE)), emptyList(),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance).resolve(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))

        val result = executeLinearSource(SourceExecutionRequest(invocation), SourceExecutionCallbacks()) { _, _, bind ->
            assertNotNull(bind).invoke(Cancellation.Never, false)
            SourceExecutionResult.Refuted(invocation)
        }

        val outcome = assertNotNull(result.linearSource)
        assertFalse(outcome.nativeClaimsUnrestricted)
        assertEquals(LinearSourceConclusion.UNKNOWN, outcome.conclusion)
    }

    @Test
    fun `a native refutation should report under the selected certification policy`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ZERO, SemanticRational.ONE)),
            listOf(LinearSourceRow(emptyList(), lower = SemanticRational.of(BIG_ONE, bigIntOf(100_000_000)))),
            semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance).resolve(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.SATISFY, FiniteEngine.FIXED))

        val result = executeLinearSource(SourceExecutionRequest(invocation), SourceExecutionCallbacks()) { _, _, _ ->
            SourceExecutionResult.Refuted(invocation)
        }

        val outcome = assertNotNull(result.linearSource)
        assertEquals(LinearSourceConclusion.UNSATISFIABLE, outcome.conclusion)
        assertEquals(!exact, outcome.numericalClaims)
        assertEquals(exact, outcome.claimsCoverTolerance)
    }
}
