package com.eignex.klause.util

/**
 * Closed real interval `[lo, hi]` over IEEE-754 doubles, declaring a float variable's range.
 *
 * Compilation lowers each float onto an integer bucket index over this interval (or an LP-only real
 * column), so the interval is approximated with finite resolution and solutions may lie within rounding
 * error of the bound.
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
