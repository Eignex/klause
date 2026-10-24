package com.eignex.klause.factor.table.internals

internal class TableLsState(val dist: IntArray, arity: Int) {
    private val heads = IntArray(arity + 1) { -1 }
    private val next = IntArray(dist.size) { -1 }
    private val previous = IntArray(dist.size) { -1 }
    private val counts = IntArray(arity + 1)
    private val probeCounts = IntArray(arity + 1)
    private var probeSeen: IntArray? = null
    private var probeStamp = 0
    val minDist: Int get() = counts.indexOfFirst { it != 0 }

    init {
        for (row in dist.indices.reversed()) insert(row)
    }

    fun setDistance(row: Int, distance: Int) {
        val old = dist[row]
        if (old == distance) return
        val before = previous[row]
        val after = next[row]
        if (before < 0) heads[old] = after else next[before] = after
        if (after >= 0) previous[after] = before
        counts[old]--
        dist[row] = distance
        insert(row)
    }

    fun beginProbe(repeated: Boolean) {
        counts.copyInto(probeCounts)
        if (repeated) {
            if (probeSeen == null) probeSeen = IntArray(dist.size)
            if (probeStamp == Int.MAX_VALUE) {
                probeSeen?.fill(0)
                probeStamp = 0
            }
            probeStamp++
        }
    }

    fun visitProbeRow(row: Int): Boolean {
        val seen = checkNotNull(probeSeen)
        if (seen[row] == probeStamp) return false
        seen[row] = probeStamp
        return true
    }

    fun scoreProbeRow(row: Int, delta: Int) {
        probeCounts[dist[row]]--
        probeCounts[dist[row] + delta]++
    }

    fun probeMinimum(): Int = probeCounts.indexOfFirst { it != 0 }

    fun closestRows(poll: () -> Unit = {}): IntArray {
        val result = IntArray(minOf(2, dist.size))
        var size = 0
        for (distance in heads.indices) {
            var first = -1
            var second = -1
            var row = heads[distance]
            while (row >= 0) {
                poll()
                if (first < 0 || row < first) {
                    second = first
                    first = row
                } else if (second < 0 || row < second) {
                    second = row
                }
                row = next[row]
            }
            if (first < 0) continue
            result[size++] = first
            if (size < result.size && second >= 0) result[size++] = second
            if (size == result.size) break
        }
        return result
    }

    private fun insert(row: Int) {
        val distance = dist[row]
        val head = heads[distance]
        previous[row] = -1
        next[row] = head
        if (head >= 0) previous[head] = row
        heads[distance] = row
        counts[distance]++
    }
}
