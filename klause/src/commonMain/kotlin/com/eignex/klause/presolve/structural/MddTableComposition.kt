package com.eignex.klause.presolve.structural

import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Table
import com.eignex.klause.factor.table.internals.MddTransitionIndex
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableLongIntMap

internal object MddTableComposition {
    fun intersect(
        mdd: Mdd,
        table: Table,
        visit: () -> Boolean,
        limits: ExtensionalComposition.Limits,
    ): ExtensionalIntersection? {
        if (mdd.seq.size > ExtensionalComposition.MAX_SCOPE || mdd.seq.toSet().size != mdd.seq.size ||
            mdd.transitions.size / mdd.recordStride > limits.records
        ) return null
        val columns = IntArray(mdd.seq.size) { table.xs.indexOf(mdd.seq[it]) }
        if (columns.count { it >= 0 } < 2) return null
        var stateCount = 0L
        for (count in mdd.numStatesPerLayer) {
            if (!visit() || count < 0) return null
            stateCount += count
        }
        if (stateCount > limits.states || mdd.initial !in 0 until mdd.numStatesPerLayer[0]) return null
        for (layer in mdd.seq.indices) {
            if (!visit()) return null
            val start = mdd.layerStarts[layer]
            val end = mdd.layerStarts[layer + 1]
            if (start < 0 || start > end || end > mdd.transitions.size ||
                start % mdd.recordStride != 0 || end % mdd.recordStride != 0
            ) return null
        }
        // Index construction is atomic after the bounded input has been charged and polled.
        repeat(mdd.transitions.size / mdd.recordStride) { if (!visit()) return null }
        val index = MddTransitionIndex.build(
            mdd.transitions, mdd.layerStarts, mdd.numStatesPerLayer, mdd.recordStride,
        )
        val suffix = Array(mdd.seq.size + 1) { BooleanArray(mdd.numStatesPerLayer[it]) }
        for (state in mdd.accepting) {
            if (!visit()) return null
            if (state in suffix.last().indices) suffix.last()[state] = true
        }
        for (layer in mdd.seq.indices.reversed()) {
            for (state in suffix[layer].indices) {
                if (!visit()) return null
                for (slot in index.fwdHead[layer][state] until index.fwdHead[layer][state + 1]) {
                    if (!visit()) return null
                    val pointer = index.fwdPtr[layer][slot]
                    val target = mdd.transitions[pointer + 2].toInt()
                    if (target in suffix[layer + 1].indices && suffix[layer + 1][target]) suffix[layer][state] = true
                }
            }
        }
        if (!suffix[0][mdd.initial]) return ExtensionalIntersection.Refuted
        val trie = buildTrie(table, columns, visit, limits.states) ?: return null
        return product(mdd, index, suffix, columns, trie, visit, limits)
    }

    private fun buildTrie(
        table: Table,
        columns: IntArray,
        visit: () -> Boolean,
        maxStates: Int,
    ): List<MutableLongIntMap>? {
        val selected = columns.filter { it >= 0 }
        val nodes = arrayListOf(MutableLongIntMap())
        for (row in 0 until table.numTuples) {
            var node = 0
            for ((depth, column) in selected.withIndex()) {
                if (!visit()) return null
                val value = table.tuples[row * table.arity + column]
                var next = nodes[node].getOrDefault(value, -1)
                if (next < 0) {
                    next = if (depth == selected.lastIndex) 0 else {
                        if (nodes.size >= maxStates) return null
                        nodes.size.also { nodes.add(MutableLongIntMap()) }
                    }
                    nodes[node].put(value, next)
                }
                node = next
            }
        }
        return nodes
    }

    @Suppress("LongParameterList")
    private fun product(
        mdd: Mdd,
        index: MddTransitionIndex,
        suffix: Array<BooleanArray>,
        columns: IntArray,
        trie: List<MutableLongIntMap>,
        visit: () -> Boolean,
        limits: ExtensionalComposition.Limits,
    ): ExtensionalIntersection? {
        val counts = IntArray(mdd.seq.size + 1)
        counts[0] = 1
        val starts = IntArray(mdd.seq.size + 1)
        val records = LongArrayList()
        var current = arrayListOf(pair(mdd.initial, 0))
        var states = 1
        var restricted = false
        for (layer in mdd.seq.indices) {
            starts[layer] = records.size
            val next = ArrayList<Long>()
            val ids = MutableLongIntMap()
            for ((source, key) in current.withIndex()) {
                if (!visit()) return null
                val original = (key ushr 32).toInt()
                val node = key.toInt()
                for (slot in index.fwdHead[layer][original] until index.fwdHead[layer][original + 1]) {
                    if (!visit()) return null
                    val pointer = index.fwdPtr[layer][slot]
                    val target = mdd.transitions[pointer + 2].toInt()
                    if (target !in suffix[layer + 1].indices || !suffix[layer + 1][target]) continue
                    val value = mdd.transitions[pointer + 1]
                    val child = if (columns[layer] < 0) node else trie[node].getOrDefault(value, -1)
                    if (child < 0) {
                        restricted = true
                        continue
                    }
                    val nextKey = pair(target, child)
                    var destination = ids.getOrDefault(nextKey, -1)
                    if (destination < 0) {
                        if (states >= limits.states) return null
                        states++
                        destination = next.size
                        ids.put(nextKey, destination)
                        next.add(nextKey)
                    }
                    if (records.size / mdd.recordStride >= limits.records) return null
                    records.add(source.toLong())
                    records.add(value)
                    records.add(destination.toLong())
                    if (mdd.recordStride == 4) records.add(mdd.transitions[pointer + 3])
                }
            }
            if (next.isEmpty()) return ExtensionalIntersection.Refuted
            counts[layer + 1] = next.size
            current = next
        }
        if (!restricted) return null
        starts[mdd.seq.size] = records.size
        return trim(mdd, counts, starts, records, visit)?.let { ExtensionalIntersection.Restricted(it) }
    }

    private fun trim(
        mdd: Mdd,
        counts: IntArray,
        starts: IntArray,
        records: LongArrayList,
        visit: () -> Boolean,
    ): Mdd? {
        val live = Array(counts.size) { BooleanArray(counts[it]) }
        live.last().fill(true)
        for (layer in mdd.seq.indices.reversed()) {
            var pointer = starts[layer]
            while (pointer < starts[layer + 1]) {
                if (!visit()) return null
                if (live[layer + 1][records[pointer + 2].toInt()]) live[layer][records[pointer].toInt()] = true
                pointer += mdd.recordStride
            }
        }
        val ids = Array(counts.size) { layer ->
            var next = 0
            IntArray(counts[layer]) { state ->
                if (!visit()) return null
                if (live[layer][state]) next++ else -1
            }.also { counts[layer] = next }
        }
        val kept = LongArrayList()
        val offsets = IntArray(starts.size)
        for (layer in mdd.seq.indices) {
            offsets[layer] = kept.size
            var pointer = starts[layer]
            while (pointer < starts[layer + 1]) {
                if (!visit()) return null
                val source = ids[layer][records[pointer].toInt()]
                val target = ids[layer + 1][records[pointer + 2].toInt()]
                if (source >= 0 && target >= 0) {
                    kept.add(source.toLong())
                    kept.add(records[pointer + 1])
                    kept.add(target.toLong())
                    if (mdd.recordStride == 4) kept.add(records[pointer + 3])
                }
                pointer += mdd.recordStride
            }
        }
        offsets[mdd.seq.size] = kept.size
        return Mdd(
            mdd.seq, counts, offsets, kept.toLongArray(), 0, IntArray(counts.last()) { it },
            mdd.recordStride, mdd.cost,
        )
    }

    private fun pair(state: Int, node: Int): Long = (state.toLong() shl 32) or (node.toLong() and 0xffffffffL)
}
