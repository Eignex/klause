package com.eignex.klause.factor.table.internals

internal class TableLsState(val dist: IntArray, arity: Int) {
    private val heads = IntArray(arity + 1) { -1 }
    private val next = IntArray(dist.size) { -1 }
    private val previous = IntArray(dist.size) { -1 }
    private val counts = IntArray(arity + 1)
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

    fun closestRows(): IntArray {
        val result = IntArray(minOf(2, dist.size))
        var size = 0
        for (distance in heads.indices) {
            var first = -1
            var second = -1
            var row = heads[distance]
            while (row >= 0) {
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
