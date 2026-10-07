package com.eignex.klause.lowering

import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray

internal fun CnfLowering.reifyRealLinear(
    coefficients: DoubleArray,
    variables: IntArray,
    op: LinearOp,
    bound: Double,
    strict: Boolean = false,
): Int {
    if (variables.isEmpty()) {
        val holds = when (op) {
            LinearOp.LE -> if (strict) 0.0 < bound else 0.0 <= bound
            LinearOp.GE -> if (strict) 0.0 > bound else 0.0 >= bound
            LinearOp.EQ -> 0.0 == bound
            LinearOp.NE -> 0.0 != bound
        }
        return if (holds) trueLit() else Lit.negate(trueLit())
    }
    return when (op) {
        LinearOp.EQ -> tseitinAnd(
            listOf(
                reifyRealLinear(coefficients, variables, LinearOp.LE, bound),
                reifyRealLinear(coefficients, variables, LinearOp.GE, bound),
            ),
        )

        LinearOp.NE -> Lit.negate(reifyRealLinear(coefficients, variables, LinearOp.EQ, bound))

        LinearOp.LE, LinearOp.GE -> {
            val aux = newBool()
            factors.add(
                ReifiedRealLinear(aux, EmptyIntArray, EmptyDoubleArray, variables, coefficients, op, bound, strict),
            )
            Lit.make(aux, true)
        }
    }
}
