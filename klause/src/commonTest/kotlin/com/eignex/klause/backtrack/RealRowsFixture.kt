package com.eignex.klause.backtrack

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem

internal fun manyRealRows(offset: Long, reals: Int) = Problem(
    numBoolVars = 0,
    numIntVars = 1,
    intDomains = arrayOf(IntDomain(0, 1)),
    factors = Array<Factor>(1001) {
        val column = it % (reals - 1)
        Linear(longArrayOf(), intArrayOf(), doubleArrayOf(1.0, -1.0), intArrayOf(column, column + 1),
            LinearOp.LE, offset - it)
    },
    numRealVars = reals,
    realLower = DoubleArray(reals),
    realUpper = DoubleArray(reals) { 1.0 },
)
