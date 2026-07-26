package com.eignex.klause.solver.intdomain

import com.eignex.klause.solver.IntConsumer
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.util.LongArrayList

/** Interval-run list: flattened sorted disjoint present runs `[lo0,hi0, lo1,hi1, …]`, `>= 2` runs,
 *  strict gaps between them, `runs[0] == min`, `runs[last] == max`. */
internal class RunsDomain(override val min: Long, override val max: Long, private val runs: LongArray) :
    AbstractIntDomain() {
    init {
        require(min <= max) { "Empty domain: $min..$max" }
        require(runs.size >= 4 && runs.size % 2 == 0) { "RunsDomain needs >= 2 runs" }
    }

    // Exact present count, saturated at Long.MAX_VALUE (a single run may span beyond 63 bits, making
    // even the Long sum wrap; one wrapped addend is always negative, so the check catches it).
    private val exactSize: Long = run {
        var s = 0L
        var k = 0
        while (k < runs.size) {
            val len = runs[k + 1] - runs[k] + 1
            s = if (len < 0L || s + len < 0L) Long.MAX_VALUE else s + len
            if (s == Long.MAX_VALUE) break
            k += 2
        }
        s
    }

    // Saturates at Int.MAX_VALUE (a run may span more than 32 bits); such a domain is never enumerated.
    override val size: Int = exactSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override val enumerable: Boolean get() = exactSize <= Int.MAX_VALUE.toLong()

    override val sizeLong: Long get() = exactSize

    // Summed from the gaps between consecutive runs, so it stays exact even when `size` saturates.
    override val holeCount: Long = run {
        var holes = 0L
        var k = 2
        while (k < runs.size) {
            holes += runs[k] - runs[k - 1] - 1
            k += 2
        }
        holes
    }

    /** Index of the run containing [value], or `-1`. Assumes `value in min..max`. */
    private fun runIndexContaining(value: Long): Int {
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
    private fun runFirstEndGe(from: Long): Int {
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

    override fun contains(value: Long): Boolean = value in min..max && runIndexContaining(value) >= 0

    // The interface defaults binary-search positions in `0 until size`; on a saturated [size] that
    // misses every value past index Int.MAX_VALUE and returns a wrong neighbour. Search the runs
    // instead — exact at any width, and O(log runs) rather than O(log size) valueAt walks.
    override fun lower(value: Long): Long {
        val cand = value - 1
        if (cand in this) return cand
        // Last run starting below [value]; its end is the nearest present value on the left (the run
        // cannot reach cand, or the fast path above would have hit).
        var lo = 0
        var hi = (runs.size shr 1) - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (runs[mid shl 1] < value) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return minOf(runs[(ans shl 1) + 1], cand)
    }

    override fun higher(value: Long): Long {
        val cand = value + 1
        if (cand in this) return cand
        // First run starting above [value]; its start is the nearest present value on the right.
        var lo = 0
        var hi = (runs.size shr 1) - 1
        var ans = hi
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (runs[mid shl 1] > value) {
                ans = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }
        return runs[ans shl 1]
    }

    override fun valueAt(i: Int): Long {
        var rem = i
        var k = 0
        while (k < runs.size) {
            val len = runs[k + 1] - runs[k] + 1
            if (rem < len) return runs[k] + rem
            // Only reached when rem >= len, so len <= rem <= Int.MAX and this narrowing is exact.
            rem -= len.toInt()
            k += 2
        }
        error("valueAt($i) out of range; size=$size")
    }

    override fun excludeValue(value: Long): IntDomain {
        if (!contains(value)) return this
        val k = runIndexContaining(value)
        val rlo = runs[k shl 1]
        val rhi = runs[(k shl 1) + 1]
        val out = LongArrayList(runs.size + 2)
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
        return intDomainFromRuns(out)
    }

    override fun withMinAtLeast(newMin: Long): IntDomain {
        if (newMin <= min) return this
        check(newMin <= max) { "withMinAtLeast($newMin) empties domain [$min..$max]" }
        val out = LongArrayList(runs.size)
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
        return intDomainFromRuns(out)
    }

    override fun withMaxAtMost(newMax: Long): IntDomain {
        if (newMax >= max) return this
        check(newMax >= min) { "withMaxAtMost($newMax) empties domain [$min..$max]" }
        val out = LongArrayList(runs.size)
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
        return intDomainFromRuns(out)
    }

    override fun includeInteriorValue(value: Long): IntDomain {
        require(value > min && value < max) { "includeInteriorValue($value) outside ($min, $max)" }
        val rcount = runs.size shr 1
        // `value` is a hole strictly inside the bounds: it lies in the gap between run k (ends below
        // value) and run k+1 (starts above value).
        var k = 0
        while (k < rcount - 1 && !(runs[(k shl 1) + 1] < value && value < runs[(k + 1) shl 1])) k++
        val touchLeft = value == runs[(k shl 1) + 1] + 1
        val touchRight = value == runs[(k + 1) shl 1] - 1
        val out = LongArrayList(runs.size + 2)
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
        return intDomainFromRuns(out)
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

    override fun forEachHoleInRange(lo: Long, hi: Long, action: IntConsumer) {
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
