package com.eignex.klause.solver.factor

import kotlin.math.max
import kotlin.math.min

/**
 * Shared mandatory-profile (compulsory-part) machinery for the time-tabling propagators
 * [Cumulative], [Cumulatives], and [Disjunctive].
 *
 * A present task with start domain `[s.min, s.max]` and fixed duration `d` has a
 * *compulsory part* `[lst, ect) = [s.max, s.min + d)` that it must occupy wherever it
 * starts; the part is non-empty iff `lst < ect`. Summing the compulsory parts of all
 * present tasks (each weighted by its resource demand) yields the mandatory profile: a
 * set of disjoint segments `[from, to)` each at a constant resource level. A level above
 * the capacity proves infeasibility, and a candidate placement that would push any
 * segment over capacity can be shaved.
 *
 * This consolidates three previously copy-pasted bodies — the event build, the
 * `(time asc, delta desc)` sort, the segment-build sweep, and the `ownsMandatory`
 * discount in [overloadsAt] — into one place, so the subtle own-part discount no longer
 * has to be kept in sync by hand. Disjunctive is the capacity-1, unit-resource
 * specialization (`build(cap = 1)`, `overloadsAt(..., r = 1, cap = 1, ...)`).
 *
 * One instance covers a single profile (build once, then query); callers needing several
 * profiles (e.g. one per machine in [Cumulatives]) allocate one instance each.
 */
internal class MandatoryProfile {
    private val events = ArrayList<IntArray>()
    private var segFromA = IntArray(0)
    private var segToA = IntArray(0)
    private var segLevelA = IntArray(0)

    /** Number of mandatory segments produced by the most recent [build]. */
    var segCount = 0
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
                if (level > cap) return false
            }
        }
        return true
    }

    /**
     * True iff placing a task occupying `[s, sPlusD)` with resource demand [r] would push
     * some mandatory segment over [cap] — after discounting the task's own already-counted
     * compulsory part on the overlapping range when [ownsMandatory] (its compulsory window
     * is `[lstI, ectI)`).
     */
    fun overloadsAt(s: Int, sPlusD: Int, r: Int, cap: Int, ownsMandatory: Boolean, lstI: Int, ectI: Int): Boolean {
        for (k in 0 until segCount) {
            val from = segFromA[k]
            val to = segToA[k]
            if (to <= s || from >= sPlusD) continue
            var effective = segLevelA[k]
            if (ownsMandatory) {
                val ovFrom = max(from, lstI)
                val ovTo = min(to, ectI)
                if (ovFrom < ovTo) effective -= r
            }
            if (effective + r > cap) return true
        }
        return false
    }
}
