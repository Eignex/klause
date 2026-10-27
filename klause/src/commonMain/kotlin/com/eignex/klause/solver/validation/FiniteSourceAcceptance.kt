package com.eignex.klause.solver.validation

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample

internal class FiniteSourceAcceptance(
    private val accepts: (Sample) -> Boolean,
    val permitsToleranceOptimum: Boolean,
    // Source objectives can differ from a rounded working auxiliary even when their displays tie.
    val objective: ((Sample) -> BigFraction?)? = null,
) : (Sample) -> Boolean {
    override fun invoke(sample: Sample): Boolean = accepts(sample)
}

internal fun ((Sample) -> Boolean)?.permitsToleranceOptimum(): Boolean =
    this != null && (this as? FiniteSourceAcceptance)?.permitsToleranceOptimum != false

internal fun ((Sample) -> Boolean)?.floatingOptimumAcceptance(): ((Sample) -> Boolean)? {
    val check = this ?: return null
    val permitted = check.permitsToleranceOptimum()
    return { sample -> check(sample) && permitted }
}
