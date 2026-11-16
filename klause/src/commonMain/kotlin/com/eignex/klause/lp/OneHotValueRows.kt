package com.eignex.klause.lp

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.values
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList

internal inline fun RelaxationBuilder.emitOneHotValues(
    variable: Int,
    crossinline definition: (Long) -> LpAuxiliaryColumn,
    crossinline selector: (value: Long, column: Int) -> Unit,
): Boolean {
    val box = rootDomain(variable)
    val live = liveDomain(variable)
    val columns = IntArrayList()
    val values = LongArrayList()
    box.values.forEach { value ->
        val column = auxColumn(
            0L,
            if (live.contains(value)) 1L else 0L,
            presence = longArrayOf(variable.toLong(), value),
            definition = definition(value),
        )
        columns.add(column)
        values.add(value)
        selector(value, column)
    }
    if (columns.isEmpty()) return false
    row(columns.toIntArray(), LongArray(columns.size) { 1L }, LinearOp.EQ, 1L, Contribution.HULL)
    emitValueChannel(variable, columns, values)
    return true
}

internal fun RelaxationBuilder.emitValueChannel(variable: Int, columns: IntArrayList, values: LongArrayList) {
    val count = columns.size
    val channelColumns = IntArray(count + 1)
    val channelValues = LongArray(count + 1)
    for (i in 0 until count) {
        channelColumns[i] = columns[i]
        channelValues[i] = values[i]
    }
    channelColumns[count] = intColumn(variable)
    channelValues[count] = -1L
    row(channelColumns, channelValues, LinearOp.EQ, 0L, Contribution.HULL)
}
