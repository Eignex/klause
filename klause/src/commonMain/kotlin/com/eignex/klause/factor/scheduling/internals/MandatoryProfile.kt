package com.eignex.klause.factor.scheduling.internals

import com.eignex.util.arrays.emptyLongArray
import kotlin.math.max
import kotlin.math.min

// Sums the compulsory parts `[lst, ect) = [s.max, s.min + d)` of present tasks into disjoint constant-level
// segments; Disjunctive uses it with capacity 1 and unit demand. Time-tabling after Aggoun and Beldiceanu,
// "Extending CHIP in order to solve complex scheduling and placement problems",
// Math. Comput. Modelling 17(7), 1993, https://doi.org/10.1016/0895-7177(93)90068-A.
internal class MandatoryProfile {
    private val events = ArrayList<LongArray>()
    private var segFromA = emptyLongArray()
    private var segToA = emptyLongArray()
    private var segLevelA = emptyLongArray()

    var segCount = 0
        private set

    // Start of the overloaded segment after a failed build, so callers can cite exactly the covering tasks.
    var overloadTime = 0L
        private set

    fun addTask(lst: Long, ect: Long, resource: Long) {
        if (lst < ect) {
            events.add(longArrayOf(lst, resource))
            events.add(longArrayOf(ect, -resource))
        }
    }

    // False as soon as a level exceeds cap.
    fun build(cap: Long): Boolean {
        // (time asc, delta desc): at equal time, additions (+r) precede subtractions (−r).
        events.sortWith(compareBy({ it[0] }, { -it[1] }))
        if (segFromA.size < events.size) {
            segFromA = LongArray(events.size)
            segToA = LongArray(events.size)
            segLevelA = LongArray(events.size)
        }
        segCount = 0
        var level = 0L
        var cursor = if (events.isEmpty()) 0L else events[0][0]
        for ((idx, ev) in events.withIndex()) {
            val t = ev[0]
            if (t > cursor && level > 0) {
                segFromA[segCount] = cursor
                segToA[segCount] = t
                segLevelA[segCount] = level
                segCount++
            }
            level += ev[1]
            cursor = t
            if (idx == events.size - 1 || events[idx + 1][0] != t) {
                if (level > cap) {
                    overloadTime = t
                    return false
                }
            }
        }
        return true
    }

    fun maxLevelOver(from: Long, to: Long): Long {
        if (from >= to) return 0
        var lo = 0
        var hi = segCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (segToA[mid] <= from) lo = mid + 1 else hi = mid
        }
        var best = 0L
        var k = lo
        while (k < segCount && segFromA[k] < to) {
            if (segLevelA[k] > best) best = segLevelA[k]
            k++
        }
        return best
    }

    // With ownsMandatory, the task's own compulsory part `[lstI, ectI)` is discounted where it overlaps.
    fun overloadsAt(
        s: Long,
        sPlusD: Long,
        r: Long,
        cap: Long,
        ownsMandatory: Boolean,
        lstI: Long,
        ectI: Long,
    ): Boolean {
        // Segments are sorted by time, so binary-search the first one ending past [s]: this runs
        // once per task per propagation, and a linear scan dominates dense-Cumulative profiles.
        var lo = 0
        var hi = segCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (segToA[mid] <= s) lo = mid + 1 else hi = mid
        }
        var k = lo
        while (k < segCount && segFromA[k] < sPlusD) {
            var effective = segLevelA[k]
            if (ownsMandatory) {
                val ovFrom = max(segFromA[k], lstI)
                val ovTo = min(segToA[k], ectI)
                if (ovFrom < ovTo) effective -= r
            }
            if (effective + r > cap) return true
            k++
        }
        return false
    }
}
