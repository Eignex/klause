package com.eignex.klause.solver.intdomain

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.IntArrayList

internal abstract class AbstractIntDomain : IntDomain {

    override fun excludeValues(values: IntArray): IntDomain? {
        if (values.isEmpty()) return this
        val out = IntArrayList(size)
        var j = 0
        forEach { p ->
            while (j < values.size && values[j] < p) j++
            if (j < values.size && values[j] == p) j++ else out.add(p)
        }
        if (out.size == size) return this
        if (out.size == 0) return null
        return intDomainFromSurvivors(out.toIntArray())
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
        var h = min * 31 + max
        forEach { v -> h = h * 31 + v }
        return h
    }
}
