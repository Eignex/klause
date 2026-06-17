package com.eignex.klause.solver.intdomain

import com.eignex.klause.solver.IntConsumer
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.binarySearchInt

// Concrete [IntDomain] representations and their factories. See IntDomain for the rep family and
// the selection rule; the classes here are internal — callers work through the interface.

/** Shared [equals] / [hashCode] for every rep: by value *set*, so two domains with the same
 *  members compare equal regardless of which rep stores them. */
internal abstract class AbstractIntDomain : IntDomain {
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

/** Contiguous `[min..max]`, no interior holes. */
internal class ContiguousDomain(override val min: Int, override val max: Int) : AbstractIntDomain() {
    init {
        require(min <= max) { "Empty domain: $min..$max" }
    }

    override val size: Int get() = max - min + 1

    override fun contains(value: Int): Boolean = value in min..max

    override fun valueAt(i: Int): Int = min + i

    override fun excludeValue(value: Int): IntDomain {
        if (value !in min..max) return this
        return when {
            value == min -> ContiguousDomain(min + 1, max)

            value == max -> ContiguousDomain(min, max - 1)

            else -> {
                val span = max - min + 1
                if (span <= IntDomain.BITSET_THRESHOLD) {
                    val bits = LongArray((span + 63) ushr 6)
                    Bits.fillRange(bits, 0, span)
                    Bits.clear(bits, value - min)
                    BitsetDomain(min, max, bits, min)
                } else {
                    RunsDomain(min, max, intArrayOf(min, value - 1, value + 1, max))
                }
            }
        }
    }

    override fun excludeValues(values: IntArray): IntDomain? {
        if (values.isEmpty()) return this
        val out = IntArrayList()
        var j = 0
        var p = min
        while (p <= max) {
            while (j < values.size && values[j] < p) j++
            if (j < values.size && values[j] == p) j++ else out.add(p)
            p++
        }
        if (out.size == size) return this
        if (out.size == 0) return null
        return intDomainOfSurvivors(out.toIntArray())
    }

    override fun withMinAtLeast(newMin: Int): IntDomain {
        if (newMin <= min) return this
        check(newMin <= max) { "withMinAtLeast($newMin) empties domain [$min..$max]" }
        return ContiguousDomain(newMin, max)
    }

    override fun withMaxAtMost(newMax: Int): IntDomain {
        if (newMax >= max) return this
        check(newMax >= min) { "withMaxAtMost($newMax) empties domain [$min..$max]" }
        return ContiguousDomain(min, newMax)
    }

    override fun includeInteriorValue(value: Int): IntDomain =
        error("includeInteriorValue($value) on a contiguous domain")

    override fun forEach(action: IntConsumer) {
        for (v in min..max) action.accept(v)
    }

    override fun forEachHole(action: IntConsumer) = Unit

    override fun forEachHoleInRange(lo: Int, hi: Int, action: IntConsumer) = Unit

    override fun toString(): String = "IntDomain($min..$max)"
}

/** Bitset over a narrow span: bit `(value - bitsetLo)` is set iff `value` is present. The backing
 *  array keeps its construction-time length; a later bound move clears bits but never reallocates,
 *  so the surplus high words are always zero. */
internal class BitsetDomain(
    override val min: Int,
    override val max: Int,
    private val bitset: LongArray,
    private val bitsetLo: Int,
) : AbstractIntDomain() {
    init {
        require(min <= max) { "Empty domain: $min..$max" }
    }

    override val size: Int = run {
        var s = 0
        for (w in bitset.indices) s += bitset[w].countOneBits()
        s
    }

    override fun contains(value: Int): Boolean = value in min..max && Bits.has(bitset, value - bitsetLo)

    override fun valueAt(i: Int): Int {
        var remaining = i
        for (w in bitset.indices) {
            val word = bitset[w]
            val cnt = word.countOneBits()
            if (remaining < cnt) {
                var temp = word
                var n = remaining
                while (n > 0) {
                    temp = temp and (temp - 1L)
                    n--
                }
                return bitsetLo + (w shl 6) + temp.countTrailingZeroBits()
            }
            remaining -= cnt
        }
        error("valueAt($i) out of range; size=$size")
    }

    override fun excludeValue(value: Int): IntDomain {
        if (!contains(value)) return this
        val newBits = bitset.copyOf()
        Bits.clear(newBits, value - bitsetLo)
        var newMin = min
        var newMax = max
        if (value == min) {
            val firstSet = Bits.firstSet(newBits)
            check(firstSet >= 0) { "Empty domain after excludeValue($value)" }
            newMin = bitsetLo + firstSet
        }
        if (value == max) {
            val lastSet = Bits.lastSet(newBits)
            check(lastSet >= 0) { "Empty domain after excludeValue($value)" }
            newMax = bitsetLo + lastSet
        }
        return BitsetDomain(newMin, newMax, newBits, bitsetLo)
    }

    override fun excludeValues(values: IntArray): IntDomain? {
        if (values.isEmpty()) return this
        val out = IntArrayList()
        var j = 0
        forEach { p ->
            while (j < values.size && values[j] < p) j++
            if (j < values.size && values[j] == p) j++ else out.add(p)
        }
        if (out.size == size) return this
        if (out.size == 0) return null
        return intDomainOfSurvivors(out.toIntArray())
    }

    override fun withMinAtLeast(newMin: Int): IntDomain {
        if (newMin <= min) return this
        check(newMin <= max) { "withMinAtLeast($newMin) empties domain [$min..$max]" }
        val newBits = bitset.copyOf()
        Bits.clearBelow(newBits, newMin - bitsetLo)
        val firstSet = Bits.firstSet(newBits)
        check(firstSet >= 0) { "withMinAtLeast($newMin) emptied bitset domain" }
        val m = bitsetLo + firstSet
        check(m <= max) { "withMinAtLeast($newMin): only zero bits remained" }
        return BitsetDomain(m, max, newBits, bitsetLo)
    }

    override fun withMaxAtMost(newMax: Int): IntDomain {
        if (newMax >= max) return this
        check(newMax >= min) { "withMaxAtMost($newMax) empties domain [$min..$max]" }
        val newBits = bitset.copyOf()
        Bits.clearAbove(newBits, newMax - bitsetLo)
        val lastSet = Bits.lastSet(newBits)
        check(lastSet >= 0) { "withMaxAtMost($newMax) emptied bitset domain" }
        val m = bitsetLo + lastSet
        check(m >= min) { "withMaxAtMost($newMax): only zero bits remained" }
        return BitsetDomain(min, m, newBits, bitsetLo)
    }

    override fun includeInteriorValue(value: Int): IntDomain {
        require(value > min && value < max) { "includeInteriorValue($value) outside ($min, $max)" }
        val newBits = bitset.copyOf()
        Bits.set(newBits, value - bitsetLo)
        return BitsetDomain(min, max, newBits, bitsetLo)
    }

    override fun forEach(action: IntConsumer) {
        for (w in bitset.indices) {
            var word = bitset[w]
            while (word != 0L) {
                val lsb = word.countTrailingZeroBits()
                action.accept(bitsetLo + (w shl 6) + lsb)
                word = word and (word - 1L)
            }
        }
    }

    override fun forEachHole(action: IntConsumer) {
        for (v in (min + 1) until max) {
            if (!Bits.has(bitset, v - bitsetLo)) action.accept(v)
        }
    }

    override fun forEachHoleInRange(lo: Int, hi: Int, action: IntConsumer) {
        val from = if (lo > min) lo else min
        val to = if (hi < max) hi else max
        var v = from
        while (v <= to) {
            if (!Bits.has(bitset, v - bitsetLo)) action.accept(v)
            v++
        }
    }

    override fun toString(): String {
        val sb = StringBuilder("IntDomain($min..$max bitset[")
        var first = true
        forEach { v ->
            if (!first) sb.append(",")
            sb.append(v)
            first = false
        }
        return sb.append("])").toString()
    }
}

/** Interval-run list: flattened sorted disjoint present runs `[lo0,hi0, lo1,hi1, …]`, `>= 2` runs,
 *  strict gaps between them, `runs[0] == min`, `runs[last] == max`. */
internal class RunsDomain(override val min: Int, override val max: Int, private val runs: IntArray) :
    AbstractIntDomain() {
    init {
        require(min <= max) { "Empty domain: $min..$max" }
        require(runs.size >= 4 && runs.size % 2 == 0) { "RunsDomain needs >= 2 runs" }
    }

    override val size: Int = run {
        var s = 0
        var k = 0
        while (k < runs.size) {
            s += runs[k + 1] - runs[k] + 1
            k += 2
        }
        s
    }

    /** Index of the run containing [value], or `-1`. Assumes `value in min..max`. */
    private fun runIndexContaining(value: Int): Int {
        var lo = 0
        var hi = (runs.size shr 1) - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (runs[mid shl 1] <= value) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (ans < 0) return -1
        return if (value <= runs[(ans shl 1) + 1]) ans else -1
    }

    /** Smallest run index `k` with `runs[2k+1] >= from`. */
    private fun runFirstEndGe(from: Int): Int {
        var lo = 0
        var hi = (runs.size shr 1) - 1
        var ans = runs.size shr 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (runs[(mid shl 1) + 1] >= from) {
                ans = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return ans
    }

    override fun contains(value: Int): Boolean = value in min..max && runIndexContaining(value) >= 0

    override fun valueAt(i: Int): Int {
        var rem = i
        var k = 0
        while (k < runs.size) {
            val len = runs[k + 1] - runs[k] + 1
            if (rem < len) return runs[k] + rem
            rem -= len
            k += 2
        }
        error("valueAt($i) out of range; size=$size")
    }

    override fun excludeValue(value: Int): IntDomain {
        if (!contains(value)) return this
        val k = runIndexContaining(value)
        val rlo = runs[k shl 1]
        val rhi = runs[(k shl 1) + 1]
        val out = IntArrayList(runs.size + 2)
        for (i in 0 until k) {
            out.add(runs[i shl 1])
            out.add(runs[(i shl 1) + 1])
        }
        when {
            rlo == rhi -> Unit

            // singleton run vanishes
            value == rlo -> {
                out.add(rlo + 1)
                out.add(rhi)
            }

            value == rhi -> {
                out.add(rlo)
                out.add(rhi - 1)
            }

            else -> {
                out.add(rlo)
                out.add(value - 1)
                out.add(value + 1)
                out.add(rhi)
            }
        }
        for (i in k + 1 until (runs.size shr 1)) {
            out.add(runs[i shl 1])
            out.add(runs[(i shl 1) + 1])
        }
        return intDomainOfRuns(out)
    }

    override fun excludeValues(values: IntArray): IntDomain? {
        if (values.isEmpty()) return this
        val out = IntArrayList()
        var j = 0
        forEach { p ->
            while (j < values.size && values[j] < p) j++
            if (j < values.size && values[j] == p) j++ else out.add(p)
        }
        if (out.size == size) return this
        if (out.size == 0) return null
        return intDomainOfSurvivors(out.toIntArray())
    }

    override fun withMinAtLeast(newMin: Int): IntDomain {
        if (newMin <= min) return this
        check(newMin <= max) { "withMinAtLeast($newMin) empties domain [$min..$max]" }
        val out = IntArrayList(runs.size)
        val rcount = runs.size shr 1
        var k = 0
        while (k < rcount) {
            val rhi = runs[(k shl 1) + 1]
            if (rhi >= newMin) {
                val rlo = runs[k shl 1]
                out.add(if (rlo < newMin) newMin else rlo)
                out.add(rhi)
                k++
                while (k < rcount) {
                    out.add(runs[k shl 1])
                    out.add(runs[(k shl 1) + 1])
                    k++
                }
                break
            }
            k++
        }
        check(out.size > 0) { "withMinAtLeast($newMin): only holes remained above $newMin" }
        return intDomainOfRuns(out)
    }

    override fun withMaxAtMost(newMax: Int): IntDomain {
        if (newMax >= max) return this
        check(newMax >= min) { "withMaxAtMost($newMax) empties domain [$min..$max]" }
        val out = IntArrayList(runs.size)
        val rcount = runs.size shr 1
        var k = 0
        while (k < rcount) {
            val rlo = runs[k shl 1]
            if (rlo > newMax) break
            val rhi = runs[(k shl 1) + 1]
            out.add(rlo)
            out.add(if (rhi > newMax) newMax else rhi)
            k++
        }
        check(out.size > 0) { "withMaxAtMost($newMax): only holes remained below $newMax" }
        return intDomainOfRuns(out)
    }

    override fun includeInteriorValue(value: Int): IntDomain {
        require(value > min && value < max) { "includeInteriorValue($value) outside ($min, $max)" }
        val rcount = runs.size shr 1
        // `value` is a hole strictly inside the bounds: it lies in the gap between run k (ends below
        // value) and run k+1 (starts above value).
        var k = 0
        while (k < rcount - 1 && !(runs[(k shl 1) + 1] < value && value < runs[(k + 1) shl 1])) k++
        val touchLeft = value == runs[(k shl 1) + 1] + 1
        val touchRight = value == runs[(k + 1) shl 1] - 1
        val out = IntArrayList(runs.size + 2)
        var i = 0
        while (i < rcount) {
            if (i == k) {
                when {
                    touchLeft && touchRight -> { // bridge runs k and k+1
                        out.add(runs[k shl 1])
                        out.add(runs[((k + 1) shl 1) + 1])
                        i += 2
                    }

                    touchLeft -> {
                        out.add(runs[k shl 1])
                        out.add(value)
                        i++
                    }

                    touchRight -> { // extend run k+1 to the left
                        out.add(runs[k shl 1])
                        out.add(runs[(k shl 1) + 1])
                        out.add(value)
                        out.add(runs[((k + 1) shl 1) + 1])
                        i += 2
                    }

                    else -> { // isolated new singleton run
                        out.add(runs[k shl 1])
                        out.add(runs[(k shl 1) + 1])
                        out.add(value)
                        out.add(value)
                        i++
                    }
                }
            } else {
                out.add(runs[i shl 1])
                out.add(runs[(i shl 1) + 1])
                i++
            }
        }
        return intDomainOfRuns(out)
    }

    override fun forEach(action: IntConsumer) {
        var k = 0
        while (k < runs.size) {
            var v = runs[k]
            val hi = runs[k + 1]
            while (v <= hi) {
                action.accept(v)
                v++
            }
            k += 2
        }
    }

    override fun forEachHole(action: IntConsumer) {
        var k = 0
        while (k < runs.size - 2) {
            var v = runs[k + 1] + 1
            val stop = runs[k + 2]
            while (v < stop) {
                action.accept(v)
                v++
            }
            k += 2
        }
    }

    override fun forEachHoleInRange(lo: Int, hi: Int, action: IntConsumer) {
        val from = if (lo > min) lo else min
        val to = if (hi < max) hi else max
        if (from > to) return
        val rcount = runs.size shr 1
        var k = runFirstEndGe(from)
        var pos = from
        while (pos <= to && k < rcount) {
            val rlo = runs[k shl 1]
            if (pos < rlo) {
                val emitTo = if (rlo - 1 < to) rlo - 1 else to
                while (pos <= emitTo) {
                    action.accept(pos)
                    pos++
                }
            } else {
                pos = runs[(k shl 1) + 1] + 1 // skip the present run
                k++
            }
        }
    }

    override fun toString(): String {
        val sb = StringBuilder("IntDomain($min..$max runs[")
        var k = 0
        while (k < runs.size) {
            if (k > 0) sb.append(",")
            sb.append(runs[k]).append("..").append(runs[k + 1])
            k += 2
        }
        return sb.append("])").toString()
    }
}

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
        return intDomainOfSurvivors(out)
    }

    override fun excludeValues(values: IntArray): IntDomain? {
        if (values.isEmpty()) return this
        val out = IntArrayList()
        var j = 0
        for (i in survivors.indices) {
            val p = survivors[i]
            while (j < values.size && values[j] < p) j++
            if (j < values.size && values[j] == p) j++ else out.add(p)
        }
        if (out.size == size) return this
        if (out.size == 0) return null
        return intDomainOfSurvivors(out.toIntArray())
    }

    override fun withMinAtLeast(newMin: Int): IntDomain {
        if (newMin <= min) return this
        check(newMin <= max) { "withMinAtLeast($newMin) empties domain [$min..$max]" }
        val lb = survivors.binarySearchInt(newMin)
        val start = if (lb >= 0) lb else -(lb + 1) // first survivor >= newMin
        check(start < survivors.size) { "withMinAtLeast($newMin): only holes remained above $newMin" }
        return intDomainOfSurvivors(survivors.copyOfRange(start, survivors.size))
    }

    override fun withMaxAtMost(newMax: Int): IntDomain {
        if (newMax >= max) return this
        check(newMax >= min) { "withMaxAtMost($newMax) empties domain [$min..$max]" }
        val lb = survivors.binarySearchInt(newMax)
        val end = if (lb >= 0) lb + 1 else -(lb + 1) // first index strictly above newMax
        check(end > 0) { "withMaxAtMost($newMax): only holes remained below $newMax" }
        return intDomainOfSurvivors(survivors.copyOfRange(0, end))
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
        return intDomainOfSurvivors(out)
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

/**
 * Build a domain from a non-empty sorted-distinct survivor array. Picks the most compact rep:
 * gap-free ⇒ contiguous; span `<=` [IntDomain.BITSET_THRESHOLD] ⇒ bitset; otherwise the run list
 * when it is at least as compact as the survivor list (`2·runs <= survivors`), else the survivor
 * list. The array is adopted by-reference for the survivor rep, so callers must not mutate it after.
 */
internal fun intDomainOfSurvivors(sv: IntArray): IntDomain {
    val s = sv.size
    val newMin = sv[0]
    val newMax = sv[s - 1]
    val span = newMax - newMin + 1
    if (s == span) return ContiguousDomain(newMin, newMax)
    if (span <= IntDomain.BITSET_THRESHOLD) {
        val bits = LongArray((span + 63) ushr 6)
        for (i in 0 until s) Bits.set(bits, sv[i] - newMin)
        return BitsetDomain(newMin, newMax, bits, newMin)
    }
    var r = 1
    for (i in 1 until s) if (sv[i] != sv[i - 1] + 1) r++
    if (2 * r <= s) {
        val runs = IntArray(2 * r)
        var ri = 0
        var runLo = sv[0]
        for (i in 1 until s) {
            if (sv[i] != sv[i - 1] + 1) {
                runs[ri++] = runLo
                runs[ri++] = sv[i - 1]
                runLo = sv[i]
            }
        }
        runs[ri++] = runLo
        runs[ri] = sv[s - 1]
        return RunsDomain(newMin, newMax, runs)
    }
    return SurvivorsDomain(newMin, newMax, sv)
}

/**
 * Build a domain from a non-empty, normalized run list (flattened `lo,hi` pairs, sorted, disjoint
 * with strict gaps). Same selection rule as [intDomainOfSurvivors], expanding to a survivor list
 * only in the scattered (comb) case.
 */
internal fun intDomainOfRuns(runs: IntArrayList): IntDomain {
    val newMin = runs[0]
    val newMax = runs[runs.size - 1]
    if (runs.size == 2) return ContiguousDomain(newMin, newMax) // single run
    val span = newMax - newMin + 1
    val r = runs.size shr 1
    var s = 0
    var k = 0
    while (k < runs.size) {
        s += runs[k + 1] - runs[k] + 1
        k += 2
    }
    if (span <= IntDomain.BITSET_THRESHOLD) {
        val bits = LongArray((span + 63) ushr 6)
        k = 0
        while (k < runs.size) {
            Bits.fillRange(bits, runs[k] - newMin, runs[k + 1] - newMin + 1)
            k += 2
        }
        return BitsetDomain(newMin, newMax, bits, newMin)
    }
    if (2 * r <= s) return RunsDomain(newMin, newMax, runs.toIntArray())
    val sv = IntArray(s)
    var idx = 0
    k = 0
    while (k < runs.size) {
        var v = runs[k]
        val hi = runs[k + 1]
        while (v <= hi) {
            sv[idx++] = v
            v++
        }
        k += 2
    }
    return SurvivorsDomain(newMin, newMax, sv)
}

/** Packed-bitset primitives shared by the bitset rep and the factories. Bit indices are relative to
 *  the bitset's offset. */
internal object Bits {
    fun set(bits: LongArray, bit: Int) {
        bits[bit ushr 6] = bits[bit ushr 6] or (1L shl (bit and 63))
    }

    fun clear(bits: LongArray, bit: Int) {
        val w = bit ushr 6
        bits[w] = bits[w] and (1L shl (bit and 63)).inv()
    }

    fun has(bits: LongArray, bit: Int): Boolean = ((bits[bit ushr 6] ushr (bit and 63)) and 1L) != 0L

    /** Set bits `[from, to)`. */
    fun fillRange(bits: LongArray, from: Int, to: Int) {
        if (from >= to) return
        val firstWord = from ushr 6
        val lastWord = (to - 1) ushr 6
        val firstBit = from and 63
        val lastBit = (to - 1) and 63
        if (firstWord == lastWord) {
            val width = lastBit - firstBit + 1
            val mask = (if (width == 64) -1L else (1L shl width) - 1L) shl firstBit
            bits[firstWord] = bits[firstWord] or mask
            return
        }
        bits[firstWord] = bits[firstWord] or (-1L shl firstBit)
        for (w in firstWord + 1 until lastWord) bits[w] = -1L
        val tailMask = if (lastBit == 63) -1L else (1L shl (lastBit + 1)) - 1L
        bits[lastWord] = bits[lastWord] or tailMask
    }

    /** Clear all bits with index `< exclusiveBit`. */
    fun clearBelow(bits: LongArray, exclusiveBit: Int) {
        if (exclusiveBit <= 0) return
        val fullWords = exclusiveBit ushr 6
        val rem = exclusiveBit and 63
        val limit = minOf(fullWords, bits.size)
        for (w in 0 until limit) bits[w] = 0L
        if (fullWords < bits.size && rem > 0) {
            bits[fullWords] = bits[fullWords] and ((1L shl rem) - 1L).inv()
        }
    }

    /** Clear all bits with index `> inclusiveBit`. */
    fun clearAbove(bits: LongArray, inclusiveBit: Int) {
        val fromWord = (inclusiveBit + 1) ushr 6
        val rem = (inclusiveBit + 1) and 63
        if (fromWord < bits.size && rem > 0) {
            bits[fromWord] = bits[fromWord] and ((1L shl rem) - 1L)
            for (w in fromWord + 1 until bits.size) bits[w] = 0L
        } else {
            for (w in fromWord until bits.size) bits[w] = 0L
        }
    }

    fun firstSet(bits: LongArray): Int {
        for (w in bits.indices) {
            if (bits[w] != 0L) return (w shl 6) + bits[w].countTrailingZeroBits()
        }
        return -1
    }

    fun lastSet(bits: LongArray): Int {
        for (w in bits.indices.reversed()) {
            if (bits[w] != 0L) return (w shl 6) + (63 - bits[w].countLeadingZeroBits())
        }
        return -1
    }
}
