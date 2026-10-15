package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.DivModExpression
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.WideDivMod
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.abs
import com.eignex.klause.util.minus
import com.eignex.klause.util.negate
import com.eignex.klause.util.plus
import com.eignex.klause.util.times

internal fun wideDivModRows(factor: WideDivMod): List<Linear> {
    fun row(operands: List<Pair<DivModExpression, BigInt>>, op: LinearOp, bound: BigInt): Linear {
        val terms = LinkedHashMap<Int, BigInt>()
        var constant = BIG_ZERO
        for ((expression, scale) in operands) {
            constant += expression.constant * scale
            for ((variable, coefficient) in expression.coefficients) {
                terms[variable] = (terms[variable] ?: BIG_ZERO) + coefficient * scale
            }
        }
        return Linear(terms.keys.toIntArray(), terms.values.toTypedArray(), op, bound - constant)
    }
    return listOf(
        row(listOf(factor.dividend to BIG_ONE, factor.quotient to factor.divisor.negate(),
            factor.remainder to BIG_ONE.negate()), LinearOp.EQ, BIG_ZERO),
        row(listOf(factor.remainder to BIG_ONE), LinearOp.GE, BIG_ZERO),
        row(listOf(factor.remainder to BIG_ONE), LinearOp.LE, factor.divisor.abs() - BIG_ONE),
    )
}
