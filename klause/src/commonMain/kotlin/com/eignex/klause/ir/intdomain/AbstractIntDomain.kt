package com.eignex.klause.ir.intdomain

import com.eignex.klause.ir.DomainStorageSettings
import com.eignex.klause.ir.IntDomain
import com.eignex.util.collections.LongArrayList

internal abstract class AbstractIntDomain(val storage: DomainStorageSettings) : IntDomain {

    abstract fun withStorage(settings: DomainStorageSettings): IntDomain

    // Holes = span − present. Exact for the reps whose `size` is an exact present count (bitset,
    // survivors); ContiguousDomain (no holes) and RunsDomain (whose `size` saturates) override.
    override val holeCount: Long
        get() {
            val present = requireNotNull(spanOrNull()).size.toULong()
            val holes = (max - min).toULong() - (present - 1uL)
            return holes.coerceAtMost(Long.MAX_VALUE.toULong()).toLong()
        }

    override fun excludeValues(values: LongArray): IntDomain? {
        if (values.isEmpty()) return this
        // A non-enumerable (wide-span) domain must never be walked value by value: its survivor list would overflow
        // Int indexing. Each excluded value is a span-independent run split instead, and a finite exclusion set
        // cannot empty such a domain.
        val span = spanOrNull()
        if (span == null) {
            var d: IntDomain = this
            for (v in values) d = d.excludeValue(v)
            return if (d === this) this else d
        }
        // A wide domain carved to a sparse survivor set yields a tiny result, so no count-sized preallocation.
        val out = LongArrayList()
        var j = 0
        span.forEach { p ->
            while (j < values.size && values[j] < p) j++
            if (j < values.size && values[j] == p) j++ else out.add(p)
        }
        if (out.size == span.size) return this
        if (out.size == 0) return null
        return intDomainFromSurvivors(out.toLongArray(), storage)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is IntDomain) return false
        if (min != other.min || max != other.max) return false
        if (valueCount != other.valueCount) return false
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
            // Hash the holes instead of the unwalkable values. Consistent with equals: a non-enumerable domain
            // never set-equals an enumerable one, since their exact counts differ.
            forEachHole { v -> h = h * 31 + v.hashCode() }
            return h
        }
        span.forEach { v -> h = h * 31 + v.hashCode() }
        return h
    }
}
