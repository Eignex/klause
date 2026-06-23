package com.eignex.klause.solver.factor.scheduling.internals

import kotlin.math.max
import kotlin.math.min

/**
 * Shared mandatory-profile (compulsory-part) machinery for the time-tabling propagators
 * `Cumulative` and `Disjunctive`.
 *
 * A present task with start domain `[s.min, s.max]` and fixed duration `d` has a
 * *compulsory part* `[lst, ect) = [s.max, s.min + d)` that it must occupy wherever it
 * starts; the part is non-empty iff `lst < ect`. Summing the compulsory parts of all
 * present tasks (each weighted by its resource demand) yields the mandatory profile: a
 * set of disjoint segments `[from, to)` each at a constant resource level. A level above
 * the capacity proves infeasibility, and a candidate placement that would push any
 * segment over capacity can be shaved.
 *
 * Consolidating the event build, the `(time asc, delta desc)` sort, the segment-build
 * sweep, and the `ownsMandatory` discount in [overloadsAt] into one place keeps the subtle
 * own-part discount from having to be kept in sync by hand. Disjunctive is the capacity-1,
 * unit-resource specialization (`build(cap = 1)`, `overloadsAt(..., r = 1, cap = 1, ...)`).
 *
 * One instance covers a single profile (build once, then query); callers needing several
 * profiles (e.g. one per machine) allocate one instance each.
 */
internal class MandatoryProfile {
    private val events = ArrayList<IntArray>()
    private var segFromA = IntArray(0)
    private var segToA = IntArray(0)
    private var segLevelA = IntArray(0)

    /** Number of mandatory segments produced by the most recent [build]. */
    var segCount = 0
        private set

    /** When the most recent [build] returned `false`, the start time of the overloaded
     *  segment — the time point whose summed compulsory parts exceed the capacity. Lets the
     *  caller reconstruct a pointwise conflict reason citing exactly the tasks covering it.
     *  Undefined when [build] returned `true`. */
    var overloadTime = 0
        private set

    /** Record a task's compulsory part `[lst, ect)` with resource demand [resource];
     *  a no-op when the part is empty (`lst >= ect`). */
    fun addTask(lst: Int, ect: Int, resource: Int) {
        if (lst < ect) {
            events.add(intArrayOf(lst, resource))
            events.add(intArrayOf(ect, -resource))
        }
    }

    /**
     * Sweep the recorded events into mandatory-profile segments, capturing every maximal
     * interval at a positive level. Returns false (stopping early) as soon as a segment
     * level exceeds [cap] — an overload proving infeasibility.
     */
    fun build(cap: Int): Boolean {
        // (time asc, delta desc): at equal time, additions (+r) precede subtractions (−r).
        events.sortWith(compareBy({ it[0] }, { -it[1] }))
        if (segFromA.size < events.size) {
            segFromA = IntArray(events.size)
            segToA = IntArray(events.size)
            segLevelA = IntArray(events.size)
        }
        segCount = 0
        var level = 0
        var cursor = if (events.isEmpty()) 0 else events[0][0]
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

    /** The highest mandatory level over any segment intersecting `[from, to)`, or 0 if none — the
     *  peak compulsory demand a task spanning that window must coexist with. */
    fun maxLevelOver(from: Int, to: Int): Int {
        if (from >= to) return 0
        var lo = 0
        var hi = segCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (segToA[mid] <= from) lo = mid + 1 else hi = mid
        }
        var best = 0
        var k = lo
        while (k < segCount && segFromA[k] < to) {
            if (segLevelA[k] > best) best = segLevelA[k]
            k++
        }
        return best
    }

    /**
     * True iff placing a task occupying `[s, sPlusD)` with resource demand [r] would push
     * some mandatory segment over [cap] — after discounting the task's own already-counted
     * compulsory part on the overlapping range when [ownsMandatory] (its compulsory window
     * is `[lstI, ectI)`).
     */
    fun overloadsAt(s: Int, sPlusD: Int, r: Int, cap: Int, ownsMandatory: Boolean, lstI: Int, ectI: Int): Boolean {
        // Segments are sorted ascending by time (see [build]), so binary-search the first one
        // that ends past [s] instead of scanning every segment — this runs once per task per
        // propagation and dominated dense-Cumulative profiles. Then walk forward while segments
        // still start before [sPlusD] (the overlapping window).
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
