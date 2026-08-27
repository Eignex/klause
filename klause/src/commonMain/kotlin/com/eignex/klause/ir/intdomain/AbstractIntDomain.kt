package com.eignex.klause.ir.intdomain

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.util.LongArrayList

internal abstract class AbstractIntDomain : IntDomain {

    // Holes = span − present. Exact for the reps whose `size` is an exact present count (bitset,
    // survivors); ContiguousDomain (no holes) and RunsDomain (whose `size` saturates) override.
    override val holeCount: Long
        get() {
            val span = max - min
            if (span < 0) return Long.MAX_VALUE
            val present = spanOrNull(Long.MAX_VALUE)?.let { it.size.toLong() } ?: (span + 1)
            return span + 1 - present
        }

    override fun excludeValues(values: LongArray): IntDomain? {
        if (values.isEmpty()) return this
        // A non-enumerable (wide-span) domain must never be walked value-by-value: [forEach] below is
        // O(span), so a survivor pass over a > 2^31-value domain grows an unbounded list (its backing
        // array size wraps past Int and crashes). Fold the excluded values in one at a time instead — each
        // is a span-independent run split — so the cost scales with the excluded-value count, not the span.
        // A finite exclusion set cannot empty such a domain (> 2^31 values remain), so the result is never
        // null; identity is preserved when nothing was actually present to exclude.
        val span = spanOrNull()
        if (span == null) {
            var d: IntDomain = this
            for (v in values) d = d.excludeValue(v)
            return if (d === this) this else d
        }
        // Grow from a small default rather than preallocating the value count: when a wide domain is carved to a
        // sparse survivor set the result is tiny, and the count can be huge — a count-capacity
        // LongArray would waste (or exhaust) memory.
        val out = LongArrayList()
        var j = 0
        span.forEach { p ->
            while (j < values.size && values[j] < p) j++
            if (j < values.size && values[j] == p) j++ else out.add(p)
        }
        if (out.size == span.size) return this
        if (out.size == 0) return null
        return intDomainFromSurvivors(out.toLongArray())
    }

    override fun equals(other: Any?): Boolean {
        if (other !is IntDomain) return false
        if (min != other.min || max != other.max) return false
        if (holeCount != other.holeCount) return false
        val span = spanOrNull()
        if (span == null) {
            // A saturated size carries no information, but `holeCount` stays exact: with equal bounds
            // and hole counts, disjointness of this domain's holes from the other's present values
            // means the hole sets coincide — checked in O(holes), never walking the span.
            var ok = true
            forEachHole { v -> if (v in other) ok = false }
            return ok
        }
        // Sizes and bounds agree, so `this ⊆ other` ⇒ equal sets.
        var ok = true
        span.forEach { v -> if (v !in other) ok = false }
        return ok
    }

    override fun hashCode(): Int {
        var h = min.hashCode() * 31 + max.hashCode()
        val span = spanOrNull()
        if (span == null) {
            // Hash the holes instead of the (un-walkable) values. Consistent with [equals]: a
            // non-enumerable domain never set-equals an enumerable one — their exact counts differ.
            forEachHole { v -> h = h * 31 + v.hashCode() }
            return h
        }
        span.forEach { v -> h = h * 31 + v.hashCode() }
        return h
    }
}
