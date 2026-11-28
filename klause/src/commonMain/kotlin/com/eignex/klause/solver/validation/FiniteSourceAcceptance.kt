package com.eignex.klause.solver.validation

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.engine.LpBoundSlack
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.SearchExecutionControls
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.math.nextUp

internal class FiniteSourceAcceptance(
    private val accepts: (Sample) -> Boolean,
    val permitsToleranceOptimum: Boolean,
    // Source objectives can differ from a rounded working auxiliary even when their displays tie.
    val objective: ((Sample) -> BigFraction?)? = null,
    // The completion search over the same source relations, when [accepts] admits points within a tolerance.
    val search: ToleranceRealSearch? = null,
) : (Sample) -> Boolean {
    override fun invoke(sample: Sample): Boolean = accepts(sample)
}

// A search for the real coordinates of a fixed discrete point under which every source relation holds within the
// source tolerance. It is stated over the source relations that acceptance reads, so its witnesses are points that
// acceptance admits, and a refutation shows that no point holds every relation within the slack its bound admits.
internal interface ToleranceRealSearch {
    fun complete(
        discrete: Sample,
        cancellation: ExecutionControl,
        controls: SearchExecutionControls?,
        context: LpSolveContext,
    ): ToleranceSearchResult

    // The minimization of `sum(costs(r) * x(r))` over the real coordinates of [discrete] under the same reading of
    // every relation, or null when the source has a relation the search cannot state.
    fun optimize(discrete: Sample, costs: List<BigFraction>, context: LpSolveContext): RealCompletionOptimization?

    // The same search read from a frame that [lift] maps onto this one; real coordinates keep their indices.
    fun reconstructedBy(lift: (Sample) -> Sample): ToleranceRealSearch
}

// A minimization over the real coordinates of a fixed discrete point, run in rounds that each end at a point that
// beats the previous one. A slice can retain unfinished work inside a round without renewing its allowance.
internal interface RealCompletionOptimization : AutoCloseable {
    // Work charged so far, across every call.
    val work: Long

    // Runs rounds until the optimization ends, or until [shouldPause] holds or [workLimit] units have been charged by
    // this call. Safe checkpoints within rounds retain the same search and cumulative accounting.
    fun advance(
        cancellation: ExecutionControl,
        workLimit: Long = 0L,
        shouldPause: () -> Boolean = { false },
    ): RealOptimizationStep

    override fun close() = Unit
}

internal sealed interface RealOptimizationStep {
    data object Paused : RealOptimizationStep
    class Completed(val result: RealOptimizationResult) : RealOptimizationStep
}

// What a [RealCompletionOptimization] decides. Points hold every relation exactly, or within the source tolerance when
// [withinTolerance].
internal sealed interface RealOptimizationResult {
    val withinTolerance: Boolean

    class Optimum(
        val reals: List<BigFraction>,
        val value: BigFraction,
        override val withinTolerance: Boolean,
    ) : RealOptimizationResult

    // A point and a direction of the reals along which the point stays feasible and the objective descends.
    class Unbounded(
        val reals: List<BigFraction>,
        val direction: List<BigFraction>,
        override val withinTolerance: Boolean,
    ) : RealOptimizationResult

    class Refuted(override val withinTolerance: Boolean) : RealOptimizationResult

    // The minimum was not decided: a stop, a declining encoder, or an infimum the model does not attain. [incumbent]
    // is the best point found, when there is one. A complete search that refuted everything below [infimum] states it:
    // the model attains no value at or below it, and attains values arbitrarily close above it.
    class Undecided(
        val incumbent: List<BigFraction>?,
        override val withinTolerance: Boolean,
        val infimum: BigFraction? = null,
        val stop: RealOptimizationStop? = null,
    ) : RealOptimizationResult
}

internal enum class RealOptimizationStop { WORK, ALLOCATION, DIMENSION, SCALAR, PIVOTS, TIME, CANCELLED }

// [widened], the optimization within the source tolerance, and [exact] in its place when [widened] is absent or cannot
// state the point; null when neither is. Under a tolerance only a [widened] result decides the point: a caller reads an
// exact result there as points without an optimality proof or a refutation.
internal fun toleranceFirst(
    widened: RealCompletionOptimization?,
    exact: () -> RealCompletionOptimization?,
): RealCompletionOptimization? = if (widened == null) exact() else ToleranceFirstOptimization(widened, exact)

private class ToleranceFirstOptimization(
    private var active: RealCompletionOptimization,
    private var exact: (() -> RealCompletionOptimization?)?,
) : RealCompletionOptimization {
    private var settled = 0L

    override val work: Long get() = settled + active.work

    override fun advance(
        cancellation: ExecutionControl,
        workLimit: Long,
        shouldPause: () -> Boolean,
    ): RealOptimizationStep {
        val start = work
        while (true) {
            val remaining = if (workLimit == 0L) 0L else (workLimit - (work - start)).coerceAtLeast(1L)
            val step = active.advance(cancellation, remaining, shouldPause)
            val result = (step as? RealOptimizationStep.Completed)?.result ?: return step
            val stated = result !is RealOptimizationResult.Undecided || result.incumbent != null ||
                result.infimum != null || result.stop != null || cancellation()
            val fallback = exact.takeUnless { stated } ?: return step
            exact = null
            val next = fallback() ?: return step
            settled += active.work
            active.close()
            active = next
            if (shouldPause() || (workLimit > 0L && work - start >= workLimit)) return RealOptimizationStep.Paused
        }
    }

    override fun close() = active.close()
}

// This context with its numerical refutations and bounds held over every side widened by [problem]'s source slack,
// where [check] decides points through a search within that tolerance: a node or leaf is then refuted, and a bound
// prunes or propagates, only beyond the slack the leaves read. Unchanged otherwise.
internal fun LpSolveContext.readingSourceSlack(problem: Problem, check: ((Sample) -> Boolean)?): LpSolveContext {
    if (check.toleranceSearch() == null) return this
    return withSourceSlack(problem.semantics.effective.feasibilityTolerance)
}

// Presolve has no completed-point callback; its numerical deductions must still carry the captured source slack.
internal fun LpSolveContext.readingSourceSlack(problem: Problem): LpSolveContext {
    if (!problem.semantics.relaxesRows(problem.numRealVars)) return this
    return withSourceSlack(problem.semantics.effective.feasibilityTolerance)
}

private fun LpSolveContext.withSourceSlack(policy: SourceTolerance?): LpSolveContext {
    if (!numericalDeductions || policy == null || policy.isExact) return this
    fun upper(value: SemanticRational) =
        BigFraction.of(value.numerator, value.denominator).toDouble().let { if (it == 0.0) it else it.nextUp() }
    val slack = LpBoundSlack(upper(policy.absolute), upper(policy.relative))
    return copy(refutationSlack = slack, boundSlack = slack)
}

// A finite objective's coefficients over [count] real coordinates as exact costs, or null when one is not finite.
internal fun exactRealCosts(coefficients: DoubleArray, count: Int): List<BigFraction>? = List(count) { real ->
    if (real < coefficients.size) BigFraction.ofDouble(coefficients[real]) ?: return null else BigFraction.ZERO
}

internal sealed interface ToleranceSearchResult {
    val work: Long

    class Witness(val reals: List<BigFraction>, override val work: Long) : ToleranceSearchResult
    class Refuted(override val work: Long) : ToleranceSearchResult
    class Undecided(override val work: Long) : ToleranceSearchResult

    // The source has a relation the search cannot state, so the caller keeps the verdict it reached without it.
    data object Unavailable : ToleranceSearchResult {
        override val work: Long get() = 0L
    }
}

internal fun ((Sample) -> Boolean)?.permitsToleranceOptimum(): Boolean =
    this != null && (this as? FiniteSourceAcceptance)?.permitsToleranceOptimum != false

internal fun ((Sample) -> Boolean)?.toleranceSearch(): ToleranceRealSearch? =
    (this as? FiniteSourceAcceptance)?.takeIf { it.permitsToleranceOptimum }?.search

internal fun ((Sample) -> Boolean)?.floatingOptimumAcceptance(): ((Sample) -> Boolean)? {
    val check = this ?: return null
    val permitted = check.permitsToleranceOptimum()
    return { sample -> check(sample) && permitted }
}
