package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.GridAbs
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.Cancellation

internal fun gridAbsoluteValueTable(factor: GridAbs): Table {
    val tuples = LongArray(factor.image.count { it >= 0 } * 2)
    var at = 0
    for ((input, output) in factor.image.withIndex()) {
        if (output < 0) continue
        tuples[at++] = input.toLong()
        tuples[at++] = output.toLong()
    }
    return Table(intArrayOf(factor.operand, factor.result), tuples)
}

internal fun gridAbsoluteValueRows(factor: GridAbs, cancellation: Cancellation): List<LinearForm>? {
    val inputs = ArrayList<Linear>()
    val clauses = ArrayList<LinearForm>()
    for ((input, output) in factor.image.withIndex()) {
        if (cancellation()) return null
        cancellation.charge(1)
        if (output < 0) continue
        inputs.add(Linear(intArrayOf(1), intArrayOf(factor.operand), LinearOp.EQ, input))
        clauses.add(LinearForm.Disjunction(listOf(
            Linear(intArrayOf(1), intArrayOf(factor.operand), LinearOp.NE, input),
            Linear(intArrayOf(1), intArrayOf(factor.result), LinearOp.EQ, output),
        )))
    }
    if (cancellation()) return null
    return listOf(LinearForm.Disjunction(inputs)) + clauses
}
