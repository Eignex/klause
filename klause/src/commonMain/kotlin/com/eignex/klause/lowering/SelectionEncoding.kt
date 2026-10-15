package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.util.Cancellation

internal fun <T> minMaxEncoding(
    factor: ArrayMinMax,
    cancellation: Cancellation,
    row: (LinearRow) -> T,
    all: (List<T>) -> T,
    any: (List<T>) -> T,
): T {
    val bounds = ArrayList<T>(factor.xs.size)
    val equalities = ArrayList<T>(factor.xs.size)
    for (operand in factor.xs) {
        selectionCheckpoint(cancellation)
        val variables = intArrayOf(factor.result, operand)
        bounds += row(LinearRow.ofInts(variables, longArrayOf(1, -1),
            if (factor.max) LinearOp.GE else LinearOp.LE, 0))
        equalities += row(LinearRow.ofInts(variables, longArrayOf(1, -1), LinearOp.EQ, 0))
    }
    return all(bounds + any(equalities))
}

internal fun <T> elementEncoding(
    factor: Element,
    cancellation: Cancellation,
    row: (LinearRow) -> T,
    all: (List<T>) -> T,
    any: (List<T>) -> T,
): T {
    val index = intArrayOf(factor.idx)
    val offset = factor.indexOffset.toLong()
    val relations = arrayListOf(
        row(LinearRow.ofInts(index, longArrayOf(1), LinearOp.GE, offset)),
        row(LinearRow.ofInts(index, longArrayOf(1), LinearOp.LE, offset + factor.arr.size - 1)),
    )
    for (position in factor.arr.indices) {
        selectionCheckpoint(cancellation)
        val guard = row(LinearRow.ofInts(index, longArrayOf(1), LinearOp.NE, offset + position))
        val value = factor.arr[position]
        val equality = if (factor.arrIsVars) {
            LinearRow.ofInts(intArrayOf(factor.result, value.toInt()), longArrayOf(1, -1), LinearOp.EQ, 0)
        } else {
            LinearRow.ofInts(intArrayOf(factor.result), longArrayOf(1), LinearOp.EQ, value)
        }
        relations += any(listOf(guard, row(equality)))
    }
    return all(relations)
}

private fun selectionCheckpoint(cancellation: Cancellation) {
    cancellation.charge(1)
    if (cancellation()) throw SelectionEncodingInterrupted()
}

internal class SelectionEncodingInterrupted : RuntimeException("selection encoding interrupted")
