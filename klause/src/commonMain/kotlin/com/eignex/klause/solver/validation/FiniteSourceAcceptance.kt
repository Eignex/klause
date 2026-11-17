package com.eignex.klause.solver.validation

import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.SearchExecutionControls
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

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
// beats the previous one. A caller pauses it between rounds and resumes it with the next call.
internal interface RealCompletionOptimization {
    // Work charged so far, across every call.
    val work: Long

    // Runs rounds until the optimization ends, or until [shouldPause] holds or [workLimit] units have been charged by
    // this call; a positive limit is checked between rounds, and every call runs at least one round.
    fun advance(
        cancellation: ExecutionControl,
        workLimit: Long = 0L,
        shouldPause: () -> Boolean = { false },
    ): RealOptimizationStep
}

internal sealed interface RealOptimizationStep {
    data object Paused : RealOptimizationStep
    class Completed(val result: RealOptimizationResult) : RealOptimizationStep
}

// What a [RealCompletionOptimization] decides. Points hold every relation exactly, or within the source tolerance when
// [withinTolerance].
internal sealed interface RealOptimizationResult {
    val withinTolerance: Boolean

    // A point attaining the minimum.
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

    // No point holds every relation.
    class Refuted(override val withinTolerance: Boolean) : RealOptimizationResult

    // The minimum was not decided: a stop, a declining encoder, or an infimum the model does not attain. [incumbent]
    // is the best point found, when there is one.
    class Undecided(
        val incumbent: List<BigFraction>?,
        override val withinTolerance: Boolean,
    ) : RealOptimizationResult
}

// [exact], and the optimization [widened] gives in its place when [exact] refutes the point or cannot state it, as
// when it is absent. An exact optimum strains no relation, so it stands wherever one exists; null when neither is.
internal fun exactFirst(
    exact: RealCompletionOptimization?,
    widened: () -> RealCompletionOptimization?,
): RealCompletionOptimization? = if (exact == null) widened() else ExactFirstOptimization(exact, widened)

private class ExactFirstOptimization(
    private var active: RealCompletionOptimization,
    private var widened: (() -> RealCompletionOptimization?)?,
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
            val stated = result !is RealOptimizationResult.Refuted &&
                (result !is RealOptimizationResult.Undecided || result.incumbent != null || cancellation())
            val fallback = widened.takeUnless { stated } ?: return step
            widened = null
            val next = fallback() ?: return step
            settled += active.work
            active = next
            if (shouldPause() || (workLimit > 0L && work - start >= workLimit)) return RealOptimizationStep.Paused
        }
    }
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

// The tolerance search of an acceptance that admits tolerance points, or null.
internal fun ((Sample) -> Boolean)?.toleranceSearch(): ToleranceRealSearch? =
    (this as? FiniteSourceAcceptance)?.takeIf { it.permitsToleranceOptimum }?.search

internal fun ((Sample) -> Boolean)?.floatingOptimumAcceptance(): ((Sample) -> Boolean)? {
    val check = this ?: return null
    val permitted = check.permitsToleranceOptimum()
    return { sample -> check(sample) && permitted }
}
