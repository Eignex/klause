package com.eignex.klause.solver.validation

import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.search.SearchExecutionControls
import com.eignex.klause.util.Cancellation

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
        cancellation: Cancellation,
        controls: SearchExecutionControls?,
        context: LpSolveContext,
    ): ToleranceSearchResult

    // The same search read from a frame that [lift] maps onto this one; real coordinates keep their indices.
    fun reconstructedBy(lift: (Sample) -> Sample): ToleranceRealSearch
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
