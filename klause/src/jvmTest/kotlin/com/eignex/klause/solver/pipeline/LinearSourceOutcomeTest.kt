package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.OptimalityTolerance
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

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
    private val gap = if (exact) OptimalityTolerance() else OptimalityTolerance(
        absolute = SemanticRational.of(BIG_ONE, bigIntOf(10_000_000)),
        relative = SemanticRational.of(BIG_ONE, bigIntOf(10_000)),
    )
    private val numericalOnly = if (exact) LinearSourceConclusion.SATISFIABLE else LinearSourceConclusion.OPTIMAL

    @Test
    fun `a checked terminal optimum should retain its source units under the selected policy`() {
        val tenth = BigFraction.ofLong(10).reciprocal()
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ONE, SemanticRational.ofLong(2))),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0,
                SemanticRational.of(tenth.num, tenth.den)))),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance, optimalityTolerance = gap).resolve(),
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
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance, optimalityTolerance = gap).resolve(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED))
        val rejected = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0, 0.0),
            exactReals = listOf(BigFraction.ZERO, BigFraction.ZERO))
        val native = OpenTheoryExecution.Optimize(OpenTheoryOptimum.Optimal(
            OpenTheoryAssignment.Sampled(rejected), BigFraction.ZERO, SolveStats.EMPTY))

        val result = executeLinearSource(
            SourceExecutionRequest(invocation),
            SourceExecutionCallbacks(),
        ) { _, hooks, _ ->
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
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance, optimalityTolerance = gap).resolve(),
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
    fun `a rounding difference at a large optimum should agree within the numerical gap`() {
        val billion = BigFraction.ofLong(1_000_000_000)
        val value = billion + BigFraction.ofLong(1_000).reciprocal()

        val conclusion = gapConclusion(listOf(real("x", billion, billion * BigFraction.ofLong(2))),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))), listOf(value), billion)

        assertEquals(numericalOnly, conclusion)
    }

    @Test
    fun `a native optimum disagreeing with its own point beyond the gap should not be optimal`() {
        val billion = BigFraction.ofLong(1_000_000_000)

        val conclusion = gapConclusion(listOf(real("x", billion, billion * BigFraction.ofLong(2))),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
            listOf(BigFraction.ofLong(1_000_200_000)), billion)

        assertEquals(LinearSourceConclusion.SATISFIABLE, conclusion)
    }

    @Test
    fun `an objective offset should not widen the numerical gap`() {
        val offset = BigFraction.ofLong(1_000_000_000)
        val objective = LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE)),
            SemanticRational.ofLong(1_000_000_000))

        val conclusion = gapConclusion(listOf(real("x", BigFraction.ZERO, BigFraction.ofLong(2))), objective,
            listOf(BigFraction.ONE), offset + BigFraction.ONE + BigFraction.ofLong(1_000).reciprocal())

        assertEquals(LinearSourceConclusion.SATISFIABLE, conclusion)
    }

    @Test
    fun `cancelling objective terms should agree within their term magnitudes`() {
        val large = BigFraction.ofLong(100_000_000_000)
        val hundredth = BigFraction.ofLong(100).reciprocal()
        val objective = LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE),
            LinearSourceTerm(1, SemanticRational.ofLong(-1))))
        val range = listOf("x", "y").map { real(it, BigFraction.ZERO, large * BigFraction.ofLong(2)) }

        val conclusion = gapConclusion(range, objective, listOf(large + hundredth, large),
            hundredth + BigFraction.ofLong(100_000).reciprocal())

        assertEquals(numericalOnly, conclusion)
    }

    @Test
    fun `an objective over integer columns should agree with the native value exactly`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("n", true, SemanticRational.ZERO, SemanticRational.ofLong(10))),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))), semantics(),
        )
        val point = Sample(booleanArrayOf(), longArrayOf(3L), doubleArrayOf())
        val optimum = BigFraction.ofLong(3) + BigFraction.ofLong(1_000_000_000).reciprocal()

        assertEquals(LinearSourceConclusion.SATISFIABLE, nativeOptimumConclusion(source, point, optimum))
    }

    @Test
    fun `a zero optimum should agree within the absolute floor`() {
        val point = BigFraction.ofLong(100_000_000).reciprocal()

        val conclusion = gapConclusion(listOf(real("x", BigFraction.MINUS_ONE, BigFraction.ONE)),
            LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))), listOf(point), BigFraction.ZERO)

        assertEquals(numericalOnly, conclusion)
    }

    @Test
    fun `a negative maximized optimum should agree within the numerical gap`() {
        val billion = BigFraction.ofLong(1_000_000_000)
        val objective = LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ofLong(-1))),
            sense = ObjectiveSense.MAXIMIZE)

        val conclusion = gapConclusion(listOf(real("x", billion, billion * BigFraction.ofLong(2))), objective,
            listOf(billion + BigFraction.ofLong(1_000).reciprocal()), billion.negated())

        assertEquals(numericalOnly, conclusion)
    }

    @Test
    fun `an incumbent better than the proved optimum within the gap should not be optimal`() {
        val objective = LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE)))
        val proved = BigFraction.ofLong(20_001)

        val conclusion = gapConclusion(listOf(real("x", BigFraction.ZERO, BigFraction.ofLong(30_000))), objective,
            listOf(proved), proved, published = listOf(BigFraction.ofLong(20_000)))

        assertEquals(LinearSourceConclusion.SATISFIABLE, conclusion)
    }

    private fun semantics() =
        SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance, optimalityTolerance = gap).resolve()

    private fun real(name: String, lower: BigFraction, upper: BigFraction) = LinearSourceVariable(
        name, false, SemanticRational.of(lower.num, lower.den), SemanticRational.of(upper.num, upper.den),
    )

    // The native lane claims `optimum` at the real point `values`; `published` is a different accepted point.
    private fun gapConclusion(
        variables: List<LinearSourceVariable>,
        objective: LinearSourceObjective,
        values: List<BigFraction>,
        optimum: BigFraction,
        published: List<BigFraction>? = null,
    ): LinearSourceConclusion {
        val source = LinearSourceProblem(variables, emptyList(), objective, semantics())
        return nativeOptimumConclusion(source, realSample(values + optimum), optimum,
            published?.let { realSample(it + optimum) })
    }

    private fun realSample(reals: List<BigFraction>) = Sample(booleanArrayOf(), longArrayOf(),
        DoubleArray(reals.size) { reals[it].toDouble() }, exactReals = reals)

    private fun nativeOptimumConclusion(
        source: LinearSourceProblem,
        point: Sample,
        optimum: BigFraction,
        published: Sample? = null,
    ): LinearSourceConclusion {
        val maximize = source.objective?.sense == ObjectiveSense.MAXIMIZE
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED, maximize = maximize))
        val native = OpenTheoryExecution.Optimize(OpenTheoryOptimum.Optimal(
            OpenTheoryAssignment.Sampled(point), optimum, SolveStats.EMPTY))
        val result = executeLinearSource(
            SourceExecutionRequest(invocation),
            SourceExecutionCallbacks(),
        ) { _, hooks, _ ->
            published?.let { hooks.finite.onSample(it) }
            SourceExecutionResult.Linear(invocation, native)
        }
        return assertNotNull(result.linearSource).conclusion
    }

    @Test
    fun `a source better incumbent should retain numerical optimality at a tied native cost`() {
        val source = LinearSourceProblem(
            listOf(LinearSourceVariable("x", false, SemanticRational.ONE, SemanticRational.ofLong(2))),
            emptyList(), LinearSourceObjective(listOf(LinearSourceTerm(0, SemanticRational.ONE))),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = tolerance, optimalityTolerance = gap).resolve(),
        )
        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED))
        val value = BigFraction.ONE + BigFraction.ofLong(100_000_000).reciprocal()
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(value.toDouble(), 1.0),
            exactReals = listOf(value, BigFraction.ONE))
        val native = OpenTheoryExecution.Optimize(OpenTheoryOptimum.Optimal(
            OpenTheoryAssignment.Sampled(point), BigFraction.ONE, SolveStats.EMPTY))

        val result = executeLinearSource(
            SourceExecutionRequest(invocation),
            SourceExecutionCallbacks(),
        ) { _, hooks, _ ->
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
            assertNotNull(bind).invoke(ExecutionControl.Never, false)
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
