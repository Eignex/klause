package com.eignex.klause.formats.flatzinc

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.reifyRealLinear
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray

internal fun FlatZincCompiler.postExactFloatLinear(
    coefficients: DoubleArray,
    variables: IntArray,
    op: LinearOp,
    bound: Double,
    strict: Boolean = false,
    reifier: Int? = null,
) {
    if (reifier == null && op != LinearOp.NE && variables.isNotEmpty()) {
        factors.add(Linear(EmptyIntArray, EmptyDoubleArray, variables, coefficients, op, bound, strict))
        return
    }
    val atom = reifyRealLinear(coefficients, variables, op, bound, strict)
    if (reifier == null) {
        factors.add(Clause(intArrayOf(atom)))
    } else {
        factors.add(Clause(intArrayOf(Lit.negate(reifier), atom)))
        factors.add(Clause(intArrayOf(reifier, Lit.negate(atom))))
    }
}
