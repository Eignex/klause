package com.eignex.klause.compile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.model.ContinuousRealEquality
import com.eignex.klause.model.ContinuousRealExpr

internal fun Lowering.assertContinuousEquality(equality: ContinuousRealEquality) {
    val columns = HashMap<ContinuousRealExpr, Int>()
    fun lift(expression: ContinuousRealExpr): Int = columns.getOrPut(expression) {
        when (expression) {
            is ContinuousRealExpr.Affine -> {
                val terms = expression.coefficients.filterValues { it != 0.0 }
                val ids = terms.keys.map { name ->
                    require(!floatRepresentations.getValue(name).approximate) {
                        "Continuous products require exact real columns: '$name' uses a finite grid"
                    }
                    realVarIdByName.getValue(name)
                }
                if (terms.size == 1 && terms.values.single() == 1.0 && expression.constant == 0.0) {
                    ids.single()
                } else if (terms.isEmpty()) {
                    newRealVar(expression.constant, expression.constant)
                } else {
                    val result = newRealVar(
                        Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                    )
                    factors += Linear(
                        intArrayOf(), doubleArrayOf(), (ids + result).toIntArray(),
                        (terms.values + -1.0).toDoubleArray(), LinearOp.EQ, -expression.constant,
                    )
                    result
                }
            }
            is ContinuousRealExpr.Power -> {
                val base = lift(expression.base)
                val result = newRealVar(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)
                factors += Power(Power.Coordinate.Real(SourceRealId(base)), expression.exponent,
                    Power.Coordinate.Real(SourceRealId(result)))
                result
            }
            is ContinuousRealExpr.Multiply -> {
                val a = lift(expression.left)
                val b = lift(expression.right)
                val result = newRealVar(
                    Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
                )
                continuousProduct(a, b, result)
                result
            }
        }
    }
    val left = lift(equality.left)
    val right = lift(equality.right)
    factors += Linear(
        intArrayOf(), doubleArrayOf(), intArrayOf(left, right), doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0,
    )
}
