package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.search.SearchExecutionControls
import com.eignex.klause.solver.validation.FiniteSourceAcceptance
import com.eignex.klause.solver.validation.RealCompletionOptimization
import com.eignex.klause.solver.validation.ToleranceRealSearch
import com.eignex.klause.solver.validation.ToleranceSearchResult
import com.eignex.klause.solver.validation.capturedRealAcceptance
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertIs

// A search within the tolerance that states nothing, as for a source whose tolerance layout it cannot search.
internal object UnstatedToleranceSearch : ToleranceRealSearch {
    override fun complete(
        discrete: Sample,
        cancellation: ExecutionControl,
        controls: SearchExecutionControls?,
        context: LpSolveContext,
    ): ToleranceSearchResult = ToleranceSearchResult.Unavailable

    override fun optimize(
        discrete: Sample,
        costs: List<BigFraction>,
        context: LpSolveContext,
    ): RealCompletionOptimization? = null

    override fun reconstructedBy(lift: (Sample) -> Sample): ToleranceRealSearch = this
}

// A slack of 1/1024, past the float LP's own feasibility tolerance.
internal val dyadicSlack = SourceTolerance(SemanticRational.of(bigIntOf(1), bigIntOf(1024)))

// [factors] over real coordinates in `[lower(i), upper(i)]`, read within [dyadicSlack].
internal fun tolerantReals(factors: Array<Factor>, lower: DoubleArray, upper: DoubleArray) = Problem(
    0, 0, emptyArray(), factors, numRealVars = lower.size, realLower = lower, realUpper = upper,
    semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous,
        feasibilityTolerance = dyadicSlack).resolve(),
)

// Acceptance of [source] within its tolerance, of exact points only when [exactPointsOnly], with a search that cannot
// state it.
internal fun unstatedAcceptance(source: Problem, exactPointsOnly: Boolean = false): FiniteSourceAcceptance {
    val accepts = checkNotNull(capturedRealAcceptance(source, ExecutionControl.Never))
    return FiniteSourceAcceptance({ (!exactPointsOnly || it.exactReals != null) && accepts(it) }, true,
        search = UnstatedToleranceSearch)
}

class BacktrackCompletionTest {
    // `v >= 1` over `v` in `[0, bound]`.
    private fun gap(bound: Double) = tolerantReals(
        arrayOf(Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0)),
        doubleArrayOf(0.0), doubleArrayOf(bound),
    )

    // `y = abs(x)`, `x >= bound` and `y <= 0.5` over `x` in `[-1, 1]` and `y` in `[0, 2]`.
    private fun magnitude(bound: Double) = tolerantReals(
        arrayOf(
            RealAbs(0, 1),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, bound),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0), LinearOp.LE, 0.5),
        ),
        doubleArrayOf(-1.0, 0.0), doubleArrayOf(1.0, 2.0),
    )

    // Acceptance refuses floating points, so a leaf no exact point completes reaches the search within tolerance.
    private fun solve(source: Problem): SolveResult =
        BacktrackSolver(source.bake(), LpSolveContext.finite(numerical = true))
            .solve(BacktrackParams(toleranceCheck = unstatedAcceptance(source, exactPointsOnly = true)))

    @Test
    fun `a linear leaf only the slack admits should stay undecided when the tolerance search cannot run`() {
        assertIs<SolveResult.Unknown>(solve(gap(0.9995)))
    }

    @Test
    fun `a linear leaf infeasible beyond the slack should be refuted when the tolerance search cannot run`() {
        assertIs<SolveResult.Unsat>(solve(gap(0.99)))
    }

    @Test
    fun `an absolute value only the slack admits should stay undecided when the tolerance search cannot run`() {
        assertIs<SolveResult.Unknown>(solve(magnitude(0.5005)))
    }

    @Test
    fun `an absolute value beyond the slack should be refuted when the tolerance search cannot run`() {
        assertIs<SolveResult.Unsat>(solve(magnitude(0.6)))
    }
}
