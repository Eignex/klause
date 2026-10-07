package com.eignex.klause.localsearch

import com.eignex.klause.util.mixIntKey

// Each walk starts with the same hash layout, including growth order: tied regressed factors
// must be visited in the same order regardless of how wide an earlier walk was.
internal class RepairChainDegrees {
    private class Table(val capacity: Int) {
        val keys = IntArray(capacity) { -1 }
        val degrees = IntArray(capacity)
    }

    private val tables = ArrayList<Table>().also { it.add(Table(16)) }
    private var level = 0
    private var size = 0

    fun clear() {
        level = 0
        size = 0
        tables[0].keys.fill(-1)
    }

    fun record(fid: Int, degree: Int) {
        val table = tables[level]
        val mask = table.capacity - 1
        var i = mixIntKey(fid) and mask
        while (table.keys[i] >= 0) {
            if (table.keys[i] == fid) return
            i = (i + 1) and mask
        }
        table.keys[i] = fid
        table.degrees[i] = degree
        if (++size * 2 > table.capacity) grow(table)
    }

    fun worstRegressed(degrees: IntArray, weights: DoubleArray): Int {
        val table = tables[level]
        var worst = -1
        var worstScore = 0.0
        for (i in table.keys.indices) {
            val fid = table.keys[i]
            if (fid < 0) continue
            val increase = degrees[fid] - table.degrees[i]
            if (increase > 0) {
                val score = weights[fid] * increase
                if (score > worstScore) {
                    worstScore = score
                    worst = fid
                }
            }
        }
        return worst
    }

    private fun grow(old: Table) {
        level++
        val next = if (level == tables.size) {
            Table(old.capacity * 2).also { tables.add(it) }
        } else {
            tables[level].also { it.keys.fill(-1) }
        }
        val mask = next.capacity - 1
        for (i in old.keys.indices) {
            val fid = old.keys[i]
            if (fid < 0) continue
            var j = mixIntKey(fid) and mask
            while (next.keys[j] >= 0) j = (j + 1) and mask
            next.keys[j] = fid
            next.degrees[j] = old.degrees[i]
        }
    }
}
