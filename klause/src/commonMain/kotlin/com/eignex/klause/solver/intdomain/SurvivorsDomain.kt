package com.eignex.klause.solver.intdomain

import com.eignex.klause.solver.IntConsumer
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.binarySearchInt

/** Survivor list: the sorted present values, with `>= 1` interior gap. */
internal class SurvivorsDomain(override val min: Int, override val max: Int, private val survivors: IntArray) :
    AbstractIntDomain() {
    init {
        require(min <= max) { "Empty domain: $min..$max" }
        require(survivors.size >= 2) { "SurvivorsDomain needs >= 2 survivors" }
    }

    override val size: Int get() = survivors.size

    override fun contains(value: Int): Boolean = value in min..max && survivors.binarySearchInt(value) >= 0

    override fun valueAt(i: Int): Int = survivors[i]

    override fun excludeValue(value: Int): IntDomain {
        val idx = survivors.binarySearchInt(value)
        if (idx < 0) return this
        val out = IntArray(survivors.size - 1)
        survivors.copyInto(out, 0, 0, idx)
        survivors.copyInto(out, idx, idx + 1, survivors.size)
        return intDomainFromSurvivors(out)
    }

    override fun withMinAtLeast(newMin: Int): IntDomain {
        if (newMin <= min) return this
        check(newMin <= max) { "withMinAtLeast($newMin) empties domain [$min..$max]" }
        val lb = survivors.binarySearchInt(newMin)
        val start = if (lb >= 0) lb else -(lb + 1) // first survivor >= newMin
        check(start < survivors.size) { "withMinAtLeast($newMin): only holes remained above $newMin" }
        return intDomainFromSurvivors(survivors.copyOfRange(start, survivors.size))
    }

    override fun withMaxAtMost(newMax: Int): IntDomain {
        if (newMax >= max) return this
        check(newMax >= min) { "withMaxAtMost($newMax) empties domain [$min..$max]" }
        val lb = survivors.binarySearchInt(newMax)
        val end = if (lb >= 0) lb + 1 else -(lb + 1) // first index strictly above newMax
        check(end > 0) { "withMaxAtMost($newMax): only holes remained below $newMax" }
        return intDomainFromSurvivors(survivors.copyOfRange(0, end))
    }

    override fun includeInteriorValue(value: Int): IntDomain {
        require(value > min && value < max) { "includeInteriorValue($value) outside ($min, $max)" }
        val idx = survivors.binarySearchInt(value)
        require(idx < 0) { "includeInteriorValue($value): not a hole" }
        val insertAt = -(idx + 1)
        val out = IntArray(survivors.size + 1)
        survivors.copyInto(out, 0, 0, insertAt)
        out[insertAt] = value
        survivors.copyInto(out, insertAt + 1, insertAt, survivors.size)
        return intDomainFromSurvivors(out)
    }

    override fun forEach(action: IntConsumer) {
        for (i in survivors.indices) action.accept(survivors[i])
    }

    override fun forEachHole(action: IntConsumer) {
        for (i in 1 until survivors.size) {
            var v = survivors[i - 1] + 1
            val stop = survivors[i]
            while (v < stop) {
                action.accept(v)
                v++
            }
        }
    }

    override fun forEachHoleInRange(lo: Int, hi: Int, action: IntConsumer) {
        val from = if (lo > min) lo else min
        val to = if (hi < max) hi else max
        if (from > to) return
        val lb = survivors.binarySearchInt(from)
        var i = if (lb >= 0) lb else -(lb + 1) // first survivor >= from
        var pos = from
        while (pos <= to) {
            if (i < survivors.size && survivors[i] == pos) {
                i++
                pos++
            } else {
                val nextSurv = if (i < survivors.size) survivors[i] else to + 1
                val emitTo = if (nextSurv - 1 < to) nextSurv - 1 else to
                while (pos <= emitTo) {
                    action.accept(pos)
                    pos++
                }
            }
        }
    }

    override fun toString(): String = "IntDomain($min..$max survivors${survivors.toList()})"
}
