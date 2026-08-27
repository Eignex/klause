package com.eignex.klause.solver

import kotlin.random.Random

typealias IntConsumer = com.eignex.klause.ir.IntConsumer
typealias IntDomain = com.eignex.klause.ir.IntDomain
typealias IntSpan = com.eignex.klause.ir.IntSpan

/** Enumerates this domain when it is representable as a contiguous span. */
val IntDomain.values: IntSpan
    get() = this.span()

/** Selects a uniformly distributed value from this domain. */
fun IntDomain.randomValue(rng: Random): Long {
    val indexable = spanOrNull()
    if (indexable != null) return indexable.valueAt(rng.nextInt(indexable.size))
    val width = max - min
    val sample = if (width < 0L || width == Long.MAX_VALUE) rng.nextLong() else min + rng.nextLong(width + 1L)
    return clamp(sample)
}
