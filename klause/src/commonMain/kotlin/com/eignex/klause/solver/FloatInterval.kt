package com.eignex.klause.solver

/**
 * Closed real interval `[lo, hi]` over IEEE-754 doubles. Used as the domain for float
 * variables in `Problem.floatDomains`.
 *
 * Soundness note: this is a *user-facing* interval. Native-float backends (Z3 today,
 * future native interval-CP) reason within the exact rational/real semantics this
 * declares. Backends that bucket floats onto bounded integers (bit-blaster, LogicNG,
 * and currently LocalSearch / Backtrack via a lowering pass) approximate this interval
 * with finite resolution and may produce solutions within rounding error of the bound.
 */
data class FloatInterval(
    /** Inclusive lower bound. */
    val lo: Double,
    /** Inclusive upper bound. */
    val hi: Double,
) {
    init {
        require(lo.isFinite() && hi.isFinite()) {
            "FloatInterval bounds must be finite, got [$lo, $hi]"
        }
        require(lo <= hi) {
            "FloatInterval requires lo ≤ hi, got [$lo, $hi]"
        }
    }

    /** Interval width, `hi - lo`. */
    val width: Double get() = hi - lo

    /** True iff the interval is a single point. */
    val isSingleton: Boolean get() = lo == hi

    /** True iff `v` lies in `[lo, hi]`. */
    operator fun contains(v: Double): Boolean = v in lo..hi
}
