package com.eignex.klause.localsearch

import com.eignex.klause.ir.IntDomain

/** Half-width of a search window: the widest window still holds at most [Int.MAX_VALUE] values, so every
 *  move source and invariant that indexes a domain's values stays within an `Int` span. */
private const val WINDOW_RADIUS: Long = (1L shl 30) - 1

/**
 * The values local search moves an integer column over: its root domain when that domain is narrow, else a
 * window of it centred on the value nearest zero.
 *
 * Narrow means every value fits the 32-bit range and the values can be indexed by an `Int`. A wider domain —
 * one a source left open and a lane closed with an invented box, or one stated wide — would send value-walking
 * moves through more values than an `Int` counts. The window keeps the domain's holes, so every value local
 * search proposes is one the root domain admits. Searching a window only narrows where local search looks:
 * a solution outside it is one local search does not find, never one it misreports.
 */
internal fun searchWindow(domain: IntDomain): IntDomain {
    if (isNarrow(domain)) return domain
    val anchor = domain.clamp(0L)
    val lo = if (anchor - domain.min > WINDOW_RADIUS) anchor - WINDOW_RADIUS else domain.min
    val hi = if (domain.max - anchor > WINDOW_RADIUS) anchor + WINDOW_RADIUS else domain.max
    return domain.withMinAtLeast(lo).withMaxAtMost(hi)
}

/** [domains] with every wide entry replaced by its [searchWindow]; the same array when none is wide. */
internal fun searchWindows(domains: Array<IntDomain>): Array<IntDomain> {
    if (domains.all(::isNarrow)) return domains
    return Array(domains.size) { searchWindow(domains[it]) }
}

/** Whether every value of [domain] fits the 32-bit range and the values can be indexed by an `Int`. */
internal fun isNarrow(domain: IntDomain): Boolean =
    domain.min >= Int.MIN_VALUE.toLong() && domain.max <= Int.MAX_VALUE.toLong() && domain.spanOrNull() != null
