package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.AbsAffineValue
import com.eignex.klause.factor.arithmetic.AffineAbs
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.unaryMinus

internal fun absoluteValueRows(operand: Int, result: Int): List<LinearForm> = listOf(
    LinearForm.Conjunction(listOf(Linear(intArrayOf(1), intArrayOf(result), LinearOp.GE, 0))),
    LinearForm.Disjunction(
        listOf(
        Linear(intArrayOf(1, -1), intArrayOf(result, operand), LinearOp.EQ, 0),
        Linear(intArrayOf(1, 1), intArrayOf(result, operand), LinearOp.EQ, 0),
    )
    ),
)

internal fun realAbsoluteValueRows(operand: Int, result: Int): List<LinearForm> = listOf(
    LinearForm.Conjunction(listOf(Linear(intArrayOf(), doubleArrayOf(), intArrayOf(result),
        doubleArrayOf(1.0), LinearOp.GE, 0.0))),
    LinearForm.Disjunction(listOf(
        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(result, operand),
            doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0),
        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(result, operand),
            doubleArrayOf(1.0, 1.0), LinearOp.EQ, 0.0),
    )),
)

internal fun affineAbsoluteValueRows(factor: AffineAbs): List<LinearForm> = listOf(
    LinearForm.Conjunction(listOf(factor.result.comparedTo(
        AbsAffineValue(emptyMap(), BIG_ZERO), BIG_ONE, LinearOp.GE))),
    LinearForm.Disjunction(listOf(
        factor.result.comparedTo(factor.operand, BIG_ONE, LinearOp.EQ),
        factor.result.comparedTo(factor.operand, -BIG_ONE, LinearOp.EQ),
    )),
)
