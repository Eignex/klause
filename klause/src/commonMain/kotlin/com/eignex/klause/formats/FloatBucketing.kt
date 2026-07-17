package com.eignex.klause.formats

/**
 * Bucketing metadata for one float variable lowered to an integer bucket index: a float in `[lo, hi]`
 * is discretised into [buckets] uniformly-spaced values, and the solver reasons over the backing int
 * variable [varId] (domain `[0, buckets - 1]`). Shared across the front-ends (FlatZinc, MPS) so a float
 * discretises identically regardless of format, under the same `floatBuckets` / `floatScale` config.
 */
data class FloatBucketing(
    /** Backing variable id: an integer bucket-index var (domain `[0, buckets - 1]`) when [lpOnly] is
     *  false, or an LP-only continuous (real) var id when [lpOnly] is true. */
    val varId: Int,
    /** Inclusive lower bound of the float range. */
    val lo: Double,
    /** Inclusive upper bound of the float range. */
    val hi: Double,
    /** Number of uniformly-spaced buckets. */
    val buckets: Int,
    /** True when this float is lowered as an LP-only continuous variable (issue #1232) rather than a
     *  bucket index — then [varId] is a real var id and its value is read directly, not via [valueOf]. */
    val lpOnly: Boolean = false,
) {
    /** The floating value of bucket index [bucketIndex]: `lo + bucketIndex · (hi − lo) / (buckets − 1)`. */
    fun valueOf(bucketIndex: Int): Double = if (buckets <= 1) {
        lo
    } else {
        lo + bucketIndex * (hi - lo) / (buckets - 1)
    }
}
