package com.eignex.klause.localsearch

import com.eignex.klause.ir.IntDomain

// The widest window holds at most Int.MAX_VALUE values, so value-indexing sources and invariants stay in an Int span.
private const val WINDOW_RADIUS: Long = (1L shl 30) - 1

// A wide domain would send value-walking moves through more values than an Int counts, so it is searched through
// a window centred on its value nearest zero. The window keeps the domain's holes; searching it only narrows where
// local search looks, so a solution outside it is missed, never misreported.
internal fun searchWindow(domain: IntDomain): IntDomain {
    if (isNarrow(domain)) return domain
    val anchor = domain.clamp(0L)
    val lo = if (anchor - domain.min > WINDOW_RADIUS) anchor - WINDOW_RADIUS else domain.min
    val hi = if (domain.max - anchor > WINDOW_RADIUS) anchor + WINDOW_RADIUS else domain.max
    return domain.withMinAtLeast(lo).withMaxAtMost(hi)
}

internal fun searchWindows(domains: Array<IntDomain>): Array<IntDomain> {
    if (domains.all(::isNarrow)) return domains
    return Array(domains.size) { searchWindow(domains[it]) }
}

// Short windows favour early schedules and can keep the resource profile dense.
private const val SCHEDULE_WINDOW: Long = 1L shl 20

// Schedules favour early starts, so the window opens at the domain's minimum.
internal fun scheduleWindow(domain: IntDomain): IntDomain {
    if (isNarrow(domain)) return domain
    val hi = domain.min + (SCHEDULE_WINDOW - 1)
    return if (domain.max <= hi) domain else domain.withMaxAtMost(hi)
}

internal fun isNarrow(domain: IntDomain): Boolean =
    domain.min >= Int.MIN_VALUE.toLong() && domain.max <= Int.MAX_VALUE.toLong() && domain.spanOrNull() != null
