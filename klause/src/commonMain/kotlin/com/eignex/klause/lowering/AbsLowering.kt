package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp

internal fun absoluteValueRows(operand: Int, result: Int): List<LinearForm> = listOf(
    LinearForm.Conjunction(listOf(Linear(intArrayOf(1), intArrayOf(result), LinearOp.GE, 0))),
    LinearForm.Disjunction(
        listOf(
        Linear(intArrayOf(1, -1), intArrayOf(result, operand), LinearOp.EQ, 0),
        Linear(intArrayOf(1, 1), intArrayOf(result, operand), LinearOp.EQ, 0),
    )
    ),
)
