package com.eignex.klause.factor.scheduling.internals

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.values
import kotlin.math.max
import kotlin.math.min

/**
 * Visit every timeline slot whose load changes when an equal-length task footprint moves from
 * `[oldStart, +d)` to `[newStart, +d)`. The two footprints share the overlap `[overlapLo,
 * overlapHi)`, whose load is unchanged, so only the symmetric difference is visited: [onRemove]
 * for slots the task leaves, [onAdd] for slots it enters. Work is `O(|newStart − oldStart|)`,
 * not `O(d)` — a one-step shift touches two slots regardless of duration. Slots are clamped to
 * the timeline `[0, size)`.
 */
internal inline fun forEachStartShiftSlot(
    ls: CumulativeLsState,
    oldStart: Long,
    newStart: Long,
    d: Long,
    onRemove: (t: Int) -> Unit,
    onAdd: (t: Int) -> Unit,
) {
    val size = ls.usage.size
    val oldFrom = oldStart - ls.tLow
    val newFrom = newStart - ls.tLow
    val overlapLo = max(oldFrom, newFrom)
    val overlapHi = min(oldFrom + d, newFrom + d)
    val sizeL = size.toLong()
    if (overlapLo >= overlapHi) {
        for (t in oldFrom.coerceIn(0L, sizeL).toInt() until (oldFrom + d).coerceIn(0L, sizeL).toInt()) onRemove(t)
        for (t in newFrom.coerceIn(0L, sizeL).toInt() until (newFrom + d).coerceIn(0L, sizeL).toInt()) onAdd(t)
    } else {
        for (t in oldFrom.coerceIn(0L, sizeL).toInt() until overlapLo.coerceIn(0L, sizeL).toInt()) onRemove(t)
        for (t in overlapHi.coerceIn(0L, sizeL).toInt() until (oldFrom + d).coerceIn(0L, sizeL).toInt()) onRemove(t)
        for (t in newFrom.coerceIn(0L, sizeL).toInt() until overlapLo.coerceIn(0L, sizeL).toInt()) onAdd(t)
        for (t in overlapHi.coerceIn(0L, sizeL).toInt() until (newFrom + d).coerceIn(0L, sizeL).toInt()) onAdd(t)
    }
}

/** Overage Δ of shifting task from [oldStart,+d) → [newStart,+d). Pure simulation. */
fun simulateCumulativeStartDelta(ls: CumulativeLsState, oldStart: Long, newStart: Long, d: Long, r: Long): Long {
    val cap = ls.cap
    val usage = ls.usage
    var delta = 0L
    forEachStartShiftSlot(
        ls,
        oldStart,
        newStart,
        d,
        onRemove = { t ->
            val u = usage[t]
            delta += max(0L, u - r - cap) - max(0L, u - cap)
        },
        onAdd = { t ->
            val u = usage[t]
            delta += max(0L, u + r - cap) - max(0L, u - cap)
        },
    )
    return delta
}

/** Applies a start-time shift from [oldStart] to [newStart] (duration [d], resource [r]) in place. */
fun applyCumulativeStartDelta(ls: CumulativeLsState, oldStart: Long, newStart: Long, d: Long, r: Long) {
    val cap = ls.cap
    val usage = ls.usage
    var deltaOv = 0L
    forEachStartShiftSlot(
        ls,
        oldStart,
        newStart,
        d,
        onRemove = { t ->
            val u = usage[t]
            val nu = u - r
            usage[t] = nu
            deltaOv += max(0L, nu - cap) - max(0L, u - cap)
        },
        onAdd = { t ->
            val u = usage[t]
            val nu = u + r
            usage[t] = nu
            deltaOv += max(0L, nu - cap) - max(0L, u - cap)
        },
    )
    ls.overage += deltaOv
}

/** Overage Δ of duration change [s,s+oldD) → [s,s+newD) at constant r. */
fun simulateCumulativeDurDelta(ls: CumulativeLsState, s: Long, oldD: Long, newD: Long, r: Long): Long {
    if (r <= 0 || oldD == newD) return 0
    val cap = ls.cap
    val usage = ls.usage
    val tLow = ls.tLow
    val size = usage.size
    var delta = 0L
    if (newD > oldD) {
        val from = slotLo(s + oldD - tLow, size)
        val to = slotHi(s + newD - tLow, size)
        for (t in from until to) {
            val u = usage[t]
            delta += max(0L, u + r - cap) - max(0L, u - cap)
        }
    } else {
        val from = slotLo(s + newD - tLow, size)
        val to = slotHi(s + oldD - tLow, size)
        for (t in from until to) {
            val u = usage[t]
            delta += max(0L, u - r - cap) - max(0L, u - cap)
        }
    }
    return delta
}

/** Applies a duration change from [oldD] to [newD] at fixed start [s] and resource [r] in place. */
fun applyCumulativeDurDelta(ls: CumulativeLsState, s: Long, oldD: Long, newD: Long, r: Long) {
    if (r <= 0 || oldD == newD) return
    val cap = ls.cap
    val usage = ls.usage
    val tLow = ls.tLow
    val size = usage.size
    var deltaOv = 0L
    if (newD > oldD) {
        val from = slotLo(s + oldD - tLow, size)
        val to = slotHi(s + newD - tLow, size)
        for (t in from until to) {
            val u = usage[t]
            val nu = u + r
            usage[t] = nu
            deltaOv += max(0L, nu - cap) - max(0L, u - cap)
        }
    } else {
        val from = slotLo(s + newD - tLow, size)
        val to = slotHi(s + oldD - tLow, size)
        for (t in from until to) {
            val u = usage[t]
            val nu = u - r
            usage[t] = nu
            deltaOv += max(0L, nu - cap) - max(0L, u - cap)
        }
    }
    ls.overage += deltaOv
}

/** Overage Δ of resource change r → r' over fixed interval [s, s+d). */
fun simulateCumulativeResDelta(ls: CumulativeLsState, s: Long, d: Long, oldR: Long, newR: Long): Long {
    if (d <= 0 || oldR == newR) return 0
    val cap = ls.cap
    val usage = ls.usage
    val tLow = ls.tLow
    val size = usage.size
    val diff = newR - oldR
    val from = slotLo(s - tLow, size)
    val to = slotHi(s + d - tLow, size)
    var delta = 0L
    for (t in from until to) {
        val u = usage[t]
        delta += max(0L, u + diff - cap) - max(0L, u - cap)
    }
    return delta
}

/** Applies a resource change from [oldR] to [newR] over fixed interval [s, s+d) in place. */
fun applyCumulativeResDelta(ls: CumulativeLsState, s: Long, d: Long, oldR: Long, newR: Long) {
    if (d <= 0 || oldR == newR) return
    val cap = ls.cap
    val usage = ls.usage
    val tLow = ls.tLow
    val size = usage.size
    val diff = newR - oldR
    val from = slotLo(s - tLow, size)
    val to = slotHi(s + d - tLow, size)
    var deltaOv = 0L
    for (t in from until to) {
        val u = usage[t]
        val nu = u + diff
        usage[t] = nu
        deltaOv += max(0L, nu - cap) - max(0L, u - cap)
    }
    ls.overage += deltaOv
}

/** Overage Δ when capacity changes; full O(horizon) rescan. */
fun cumulativeCapacityDelta(ls: CumulativeLsState, newCap: Long): Long {
    val usage = ls.usage
    val oldCap = ls.cap
    if (newCap == oldCap) return 0
    var newOv = 0L
    for (u in usage) if (u > newCap) newOv += u - newCap
    return newOv - ls.overage
}

/** Applies a capacity change to [newCap] in place; rescans the usage array to recompute overage. */
fun applyCumulativeCapacityDelta(ls: CumulativeLsState, newCap: Long) {
    if (newCap == ls.cap) return
    var newOv = 0L
    for (u in ls.usage) if (u > newCap) newOv += u - newCap
    ls.cap = newCap
    ls.overage = newOv
}

/** Smallest value in [domain] that is ≥ [lo], or null if none. */
internal fun firstInDomainAtLeast(domain: IntDomain, lo: Long): Long? {
    if (lo > domain.max) return null
    var pick = Long.MIN_VALUE
    var found = false
    domain.values.forEach {
        if (!found && it >= lo) {
            pick = it
            found = true
        }
    }
    return if (!found) null else pick
}

/** Clamp a Long timeline offset to a valid lower slot index in `[0, size]`. */
private fun slotLo(v: Long, size: Int): Int = v.coerceIn(0L, size.toLong()).toInt()

/** Clamp a Long timeline offset to a valid upper slot bound in `[0, size]`. */
private fun slotHi(v: Long, size: Int): Int = v.coerceIn(0L, size.toLong()).toInt()
