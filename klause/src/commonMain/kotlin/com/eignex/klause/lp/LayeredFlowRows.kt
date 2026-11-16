package com.eignex.klause.lp

import com.eignex.klause.ir.LinearOp
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet
import com.eignex.util.collections.LongArrayList

internal class LayeredFlowRows(length: Int, stateCount: (Int) -> Int) {
    private val outgoing = Array(length) { arrayOfNulls<IntArrayList>(stateCount(it)) }
    private val incoming = Array(length + 1) { arrayOfNulls<IntArrayList>(stateCount(it)) }
    private val channelColumns = Array(length) { IntArrayList() }
    private val channelValues = Array(length) { LongArrayList() }
    val accepting = IntArrayList()

    fun arc(layer: Int, source: Int, target: Int, column: Int, value: Long) {
        (outgoing[layer][source] ?: IntArrayList().also { outgoing[layer][source] = it }).add(column)
        (incoming[layer + 1][target] ?: IntArrayList().also { incoming[layer + 1][target] = it }).add(column)
        channelColumns[layer].add(column)
        channelValues[layer].add(value)
    }

    fun emit(builder: RelaxationBuilder, sequence: IntArray, reachable: Array<IntHashSet>, initial: Int): Boolean {
        val source = outgoing[0][initial] ?: return false
        if (source.isEmpty()) return false
        builder.row(source.toIntArray(), LongArray(source.size) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
        for (layer in 1 until sequence.size) {
            reachable[layer].forEach { state ->
                val columns = IntArrayList()
                val values = LongArrayList()
                outgoing[layer][state]?.let {
                    for (k in 0 until it.size) {
                        columns.add(it[k])
                        values.add(1L)
                    }
                }
                incoming[layer][state]?.let {
                    for (k in 0 until it.size) {
                        columns.add(it[k])
                        values.add(-1L)
                    }
                }
                if (!columns.isEmpty()) {
                    builder.row(columns.toIntArray(), values.toLongArray(), LinearOp.EQ, 0L, Contribution.HULL)
                }
            }
        }
        if (accepting.isEmpty()) return false
        builder.row(accepting.toIntArray(), LongArray(accepting.size) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
        for (layer in sequence.indices) {
            if (channelColumns[layer].isEmpty()) return false
            builder.emitValueChannel(sequence[layer], channelColumns[layer], channelValues[layer])
        }
        return true
    }
}
