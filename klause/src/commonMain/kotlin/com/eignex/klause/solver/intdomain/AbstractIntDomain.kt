package com.eignex.klause.solver.intdomain

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.LongArrayList

internal abstract class AbstractIntDomain : IntDomain {

    // Holes = span − present. Exact for the reps whose `size` is an exact present count (bitset,
    // survivors); ContiguousDomain (no holes) and RunsDomain (whose `size` saturates) override.
    override val holeCount: Long
        get() {
            val span = max - min
            return if (span < 0) Long.MAX_VALUE else span + 1 - size
        }

    override fun excludeValues(values: LongArray): IntDomain? {
        if (values.isEmpty()) return this
        // Grow from a small default rather than preallocating [size]: when a wide domain is carved to a
        // sparse survivor set the result is tiny, and [size] can be huge (or saturated) — a `size`-capacity
        // LongArray would waste (or exhaust) memory.
        val out = LongArrayList()
        var j = 0
        forEach { p ->
            while (j < values.size && values[j] < p) j++
            if (j < values.size && values[j] == p) j++ else out.add(p)
        }
        if (out.size == size) return this
        if (out.size == 0) return null
        return intDomainFromSurvivors(out.toLongArray())
    }

    override fun equals(other: Any?): Boolean {
        if (other !is IntDomain) return false
        if (min != other.min || max != other.max || size != other.size || holeCount != other.holeCount) return false
        if (!enumerable) {
            // A saturated size carries no information, but `holeCount` stays exact: with equal bounds
            // and hole counts, disjointness of this domain's holes from the other's present values
            // means the hole sets coincide — checked in O(holes), never walking the span.
            var ok = true
            forEachHole { v -> if (v in other) ok = false }
            return ok
        }
        // Sizes and bounds agree, so `this ⊆ other` ⇒ equal sets.
        var ok = true
        forEach { v -> if (v !in other) ok = false }
        return ok
    }

    override fun hashCode(): Int {
        var h = min.hashCode() * 31 + max.hashCode()
        if (!enumerable) {
            // Hash the holes instead of the (un-walkable) values. Consistent with [equals]: a
            // non-enumerable domain never set-equals an enumerable one — their exact counts differ.
            forEachHole { v -> h = h * 31 + v.hashCode() }
            return h
        }
        forEach { v -> h = h * 31 + v.hashCode() }
        return h
    }
}
