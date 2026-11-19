package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem

internal fun manyRealRows(offset: Long, reals: Int): Problem {
    val coefficients = doubleArrayOf(1.0, -1.0)
    val columns = Array(reals - 1) { intArrayOf(it, it + 1) }
    val noIntegers = intArrayOf()
    val noCoefficients = longArrayOf()
    return Problem(
        numBoolVars = 0,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = Array<Factor>(1001) {
            Linear(noCoefficients, noIntegers, coefficients, columns[it % columns.size],
                LinearOp.LE, offset - it)
        },
        numRealVars = reals,
        realLower = DoubleArray(reals),
        realUpper = DoubleArray(reals) { 1.0 },
    )
}
