package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.IntegerConstants
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.internals.predecessorOrNull
import com.eignex.klause.factor.arithmetic.internals.successorOrNull
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal fun ReifiedLinear.emitLpRelaxation(builder: RelaxationBuilder) {
    // A wide reified row is excluded from the LP relaxation entirely — no 64-bit reading of it may
    // enter the LP; its wide propagator is the sole enforcer.
    val row = integerConstants ?: return
    try {
        if (emitExactBinaryEquality(builder, row)) return
        emitBigMRows(builder, row)
    } catch (_: CheckedLongOverflowException) {
        return
    }
}

/** Exact convex-hull rows for `aux ⇔ (c·v == bound)` when `v` has a two-value declared domain. */
private fun ReifiedLinear.emitExactBinaryEquality(builder: RelaxationBuilder, row: IntegerConstants): Boolean {
    if (op != LinearOp.EQ || vars.size != 1) return false
    val c = row.coeff(0)
    if (c == 0L) return false
    val dec = builder.declaredDomain(vars[0])
    if (dec.valueCount != 2L) return false
    val loValue = mulExact(c, dec.min)
    val hiValue = mulExact(c, dec.max)
    val vCol = builder.intColumn(vars[0])
    val auxCol = builder.boolColumn(auxBoolVar)
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

private fun ReifiedLinear.emitBigMRows(builder: RelaxationBuilder, row: IntegerConstants) {
    var lMin = 0L
    var lMax = 0L
    var lMinD = 0L
    var lMaxD = 0L
    for (k in vars.indices) {
        val c = row.coeff(k)
        val dom = builder.liveDomain(vars[k])
        val dec = builder.declaredDomain(vars[k])
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
    val a = builder.boolColumn(auxBoolVar)
    val b = row.bound

    fun emit(auxCoeff: Long, rowOp: LinearOp, rhs: Long, global: Boolean, maxSide: Boolean) {
        val cols = IntArray(vars.size + 1)
        val vals = LongArray(vars.size + 1)
        for (k in vars.indices) {
            cols[k] = builder.intColumn(vars[k])
            vals[k] = row.coeff(k)
        }
        cols[vars.size] = a
        vals[vars.size] = auxCoeff
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
