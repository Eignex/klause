package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.FactorRow
import com.eignex.klause.factor.arithmetic.internals.predecessorOrNull
import com.eignex.klause.factor.arithmetic.internals.successorOrNull
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal fun FactorRow.Doubles.emitReifiedIntegerLpRelaxation(builder: RelaxationBuilder) {
    val coeffs = integerCoeffs ?: return
    val row = IntegerRow(coeffs, requireNotNull(integerBound))
    try {
        if (emitExactBinaryEquality(builder, row)) return
        emitBigMRows(builder, row)
    } catch (_: CheckedLongOverflowException) {
        return
    }
}

/** Exact convex-hull rows for `aux ⇔ (c·v == bound)` when `v` has a two-value declared domain. */
private fun FactorRow.Doubles.emitExactBinaryEquality(builder: RelaxationBuilder, row: IntegerRow): Boolean {
    if (op != LinearOp.EQ || intVars.size != 1) return false
    val c = row.coeffs[0]
    if (c == 0L) return false
    val dec = builder.declaredDomain(intVars[0])
    if (dec.valueCount != 2L) return false
    val loValue = mulExact(c, dec.min)
    val hiValue = mulExact(c, dec.max)
    val vCol = builder.intColumn(intVars[0])
    val auxCol = builder.boolColumn(activator)
    when (row.bound) {
        hiValue -> builder.row(
            intArrayOf(vCol, auxCol),
            longArrayOf(c, -subExact(hiValue, loValue)),
            LinearOp.EQ,
            loValue,
        )

        loValue -> builder.row(
            intArrayOf(vCol, auxCol),
            longArrayOf(c, -subExact(loValue, hiValue)),
            LinearOp.EQ,
            hiValue,
        )

        else -> builder.row(intArrayOf(auxCol), longArrayOf(1L), LinearOp.EQ, 0L)
    }
    return true
}

private fun FactorRow.Doubles.emitBigMRows(builder: RelaxationBuilder, row: IntegerRow) {
    var lMin = 0L
    var lMax = 0L
    var lMinD = 0L
    var lMaxD = 0L
    for (k in intVars.indices) {
        val c = row.coeffs[k]
        val dom = builder.liveDomain(intVars[k])
        val dec = builder.declaredDomain(intVars[k])
        if (c >= 0L) {
            lMin = addExact(lMin, mulExact(c, dom.min))
            lMax = addExact(lMax, mulExact(c, dom.max))
            lMinD = addExact(lMinD, mulExact(c, dec.min))
            lMaxD = addExact(lMaxD, mulExact(c, dec.max))
        } else {
            lMin = addExact(lMin, mulExact(c, dom.max))
            lMax = addExact(lMax, mulExact(c, dom.min))
            lMinD = addExact(lMinD, mulExact(c, dec.max))
            lMaxD = addExact(lMaxD, mulExact(c, dec.min))
        }
    }
    val a = builder.boolColumn(activator)
    val b = row.bound

    fun emit(auxCoeff: Long, rowOp: LinearOp, rhs: Long, global: Boolean, maxSide: Boolean) {
        val cols = IntArray(intVars.size + 1)
        val vals = LongArray(intVars.size + 1)
        for (k in intVars.indices) {
            cols[k] = builder.intColumn(intVars[k])
            vals[k] = row.coeffs[k]
        }
        cols[intVars.size] = a
        vals[intVars.size] = auxCoeff
        builder.bigMRow(cols, vals, rowOp, rhs, global, maxSide)
    }

    when (op) {
        LinearOp.LE -> {
            val m1 = maxOf(0L, subExact(lMax, b))
            emit(m1, LinearOp.LE, addExact(b, m1), m1 == maxOf(0L, subExact(lMaxD, b)), maxSide = true)
            successorOrNull(b)?.let { boundUp ->
                val m2 = maxOf(0L, subExact(boundUp, lMin))
                emit(m2, LinearOp.GE, boundUp, m2 == maxOf(0L, subExact(boundUp, lMinD)), maxSide = false)
            }
        }

        LinearOp.GE -> {
            val m1 = maxOf(0L, subExact(b, lMin))
            emit(-m1, LinearOp.GE, subExact(b, m1), m1 == maxOf(0L, subExact(b, lMinD)), maxSide = false)
            predecessorOrNull(b)?.let { boundDown ->
                val m2 = maxOf(0L, subExact(lMax, boundDown))
                emit(-m2, LinearOp.LE, boundDown, m2 == maxOf(0L, subExact(lMaxD, boundDown)), maxSide = true)
            }
        }

        LinearOp.EQ -> {
            val mHi = maxOf(0L, subExact(lMax, b))
            emit(mHi, LinearOp.LE, addExact(b, mHi), mHi == maxOf(0L, subExact(lMaxD, b)), maxSide = true)
            val mLo = maxOf(0L, subExact(b, lMin))
            emit(-mLo, LinearOp.GE, subExact(b, mLo), mLo == maxOf(0L, subExact(b, lMinD)), maxSide = false)
        }

        LinearOp.NE -> {
            val mHi = maxOf(0L, subExact(lMax, b))
            emit(-mHi, LinearOp.LE, b, mHi == maxOf(0L, subExact(lMaxD, b)), maxSide = true)
            val mLo = maxOf(0L, subExact(b, lMin))
            emit(mLo, LinearOp.GE, b, mLo == maxOf(0L, subExact(b, lMinD)), maxSide = false)
        }
    }
}

private class IntegerRow(val coeffs: LongArray, val bound: Long)
