package com.eignex.klause.solver.intdomain

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.LongArrayList

internal abstract class AbstractIntDomain : IntDomain {

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
        if (min != other.min || max != other.max || size != other.size) return false
        // Sizes and bounds agree, so `this ⊆ other` ⇒ equal sets.
        var ok = true
        forEach { v -> if (v !in other) ok = false }
        return ok
    }

    override fun hashCode(): Int {
        var h = min.hashCode() * 31 + max.hashCode()
        forEach { v -> h = h * 31 + v.hashCode() }
        return h
    }
}
