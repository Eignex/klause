package com.eignex.klause.solver.factor.scheduling.internals

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
    oldStart: Int,
    newStart: Int,
    d: Int,
    onRemove: (t: Int) -> Unit,
    onAdd: (t: Int) -> Unit,
) {
    val size = ls.usage.size
    val oldFrom = oldStart - ls.tLow
    val newFrom = newStart - ls.tLow
    val overlapLo = max(oldFrom, newFrom)
    val overlapHi = min(oldFrom + d, newFrom + d)
    if (overlapLo >= overlapHi) {
        for (t in max(0, oldFrom) until min(size, oldFrom + d)) onRemove(t)
        for (t in max(0, newFrom) until min(size, newFrom + d)) onAdd(t)
    } else {
        for (t in max(0, oldFrom) until min(size, overlapLo)) onRemove(t)
        for (t in max(0, overlapHi) until min(size, oldFrom + d)) onRemove(t)
        for (t in max(0, newFrom) until min(size, overlapLo)) onAdd(t)
        for (t in max(0, overlapHi) until min(size, newFrom + d)) onAdd(t)
    }
}

/** Overage Δ of shifting task from [oldStart,+d) → [newStart,+d). Pure simulation. */
fun simulateCumulativeStartDelta(ls: CumulativeLsState, oldStart: Int, newStart: Int, d: Int, r: Int): Int {
    val cap = ls.cap
    val usage = ls.usage
    var delta = 0
    forEachStartShiftSlot(
        ls,
        oldStart,
        newStart,
        d,
        onRemove = { t ->
            val u = usage[t]
            delta += max(0, u - r - cap) - max(0, u - cap)
        },
        onAdd = { t ->
            val u = usage[t]
            delta += max(0, u + r - cap) - max(0, u - cap)
        },
    )
    return delta
}

/** Applies a start-time shift from [oldStart] to [newStart] (duration [d], resource [r]) in place. */
fun applyCumulativeStartDelta(ls: CumulativeLsState, oldStart: Int, newStart: Int, d: Int, r: Int) {
    val cap = ls.cap
    val usage = ls.usage
    var deltaOv = 0
    forEachStartShiftSlot(
        ls,
        oldStart,
        newStart,
        d,
        onRemove = { t ->
            val u = usage[t]
            val nu = u - r
            usage[t] = nu
            deltaOv += max(0, nu - cap) - max(0, u - cap)
        },
        onAdd = { t ->
            val u = usage[t]
            val nu = u + r
            usage[t] = nu
            deltaOv += max(0, nu - cap) - max(0, u - cap)
        },
    )
    ls.overage += deltaOv
}

/** Overage Δ of duration change [s,s+oldD) → [s,s+newD) at constant r. */
fun simulateCumulativeDurDelta(ls: CumulativeLsState, s: Int, oldD: Int, newD: Int, r: Int): Int {
    if (r <= 0 || oldD == newD) return 0
    val cap = ls.cap
    val usage = ls.usage
    val tLow = ls.tLow
    val size = usage.size
    var delta = 0
    if (newD > oldD) {
        val from = max(0, s + oldD - tLow)
        val to = min(size, s + newD - tLow)
        for (t in from until to) {
            val u = usage[t]
            delta += max(0, u + r - cap) - max(0, u - cap)
        }
    } else {
        val from = max(0, s + newD - tLow)
        val to = min(size, s + oldD - tLow)
        for (t in from until to) {
            val u = usage[t]
            delta += max(0, u - r - cap) - max(0, u - cap)
        }
    }
    return delta
}

/** Applies a duration change from [oldD] to [newD] at fixed start [s] and resource [r] in place. */
fun applyCumulativeDurDelta(ls: CumulativeLsState, s: Int, oldD: Int, newD: Int, r: Int) {
    if (r <= 0 || oldD == newD) return
    val cap = ls.cap
    val usage = ls.usage
    val tLow = ls.tLow
    val size = usage.size
    var deltaOv = 0
    if (newD > oldD) {
        val from = max(0, s + oldD - tLow)
        val to = min(size, s + newD - tLow)
        for (t in from until to) {
            val u = usage[t]
            val nu = u + r
            usage[t] = nu
            deltaOv += max(0, nu - cap) - max(0, u - cap)
        }
    } else {
        val from = max(0, s + newD - tLow)
        val to = min(size, s + oldD - tLow)
        for (t in from until to) {
            val u = usage[t]
            val nu = u - r
            usage[t] = nu
            deltaOv += max(0, nu - cap) - max(0, u - cap)
        }
    }
    ls.overage += deltaOv
}

/** Overage Δ of resource change r → r' over fixed interval [s, s+d). */
fun simulateCumulativeResDelta(ls: CumulativeLsState, s: Int, d: Int, oldR: Int, newR: Int): Int {
    if (d <= 0 || oldR == newR) return 0
    val cap = ls.cap
    val usage = ls.usage
    val tLow = ls.tLow
    val size = usage.size
    val diff = newR - oldR
    val from = max(0, s - tLow)
    val to = min(size, s + d - tLow)
    var delta = 0
    for (t in from until to) {
        val u = usage[t]
        delta += max(0, u + diff - cap) - max(0, u - cap)
    }
    return delta
}

/** Applies a resource change from [oldR] to [newR] over fixed interval [s, s+d) in place. */
fun applyCumulativeResDelta(ls: CumulativeLsState, s: Int, d: Int, oldR: Int, newR: Int) {
    if (d <= 0 || oldR == newR) return
    val cap = ls.cap
    val usage = ls.usage
    val tLow = ls.tLow
    val size = usage.size
    val diff = newR - oldR
    val from = max(0, s - tLow)
    val to = min(size, s + d - tLow)
    var deltaOv = 0
    for (t in from until to) {
        val u = usage[t]
        val nu = u + diff
        usage[t] = nu
        deltaOv += max(0, nu - cap) - max(0, u - cap)
    }
    ls.overage += deltaOv
}

/** Overage Δ when capacity changes; full O(horizon) rescan. */
fun cumulativeCapacityDelta(ls: CumulativeLsState, newCap: Int): Int {
    val usage = ls.usage
    val oldCap = ls.cap
    if (newCap == oldCap) return 0
    var newOv = 0
    for (u in usage) if (u > newCap) newOv += u - newCap
    return newOv - ls.overage
}

/** Applies a capacity change to [newCap] in place; rescans the usage array to recompute overage. */
fun applyCumulativeCapacityDelta(ls: CumulativeLsState, newCap: Int) {
    if (newCap == ls.cap) return
    var newOv = 0
    for (u in ls.usage) if (u > newCap) newOv += u - newCap
    ls.cap = newCap
    ls.overage = newOv
}
