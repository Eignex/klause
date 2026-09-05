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

/** Exact convex-hull rows for `aux ⇔ (c·v == bound)` when `v` has a two-value root box the model
 *  itself states — over an invented endpoint the box is a search restriction, and the equality it
 *  linearizes would hold only inside it. */
private fun FactorRow.Doubles.emitExactBinaryEquality(builder: RelaxationBuilder, row: IntegerRow): Boolean {
    if (op != LinearOp.EQ || intVars.size != 1) return false
    val c = row.coeffs[0]
    if (c == 0L) return false
    if (!builder.statesBothBounds(intVars[0])) return false
    val dec = builder.rootDomain(intVars[0])
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

/**
 * Indicator rows via big-M, each side gated on the row's own activation bound being one the model states.
 *
 * `lMax` and `lMin` are what the big-M is: the row is `Σ c·x ⟨op⟩ bound` slackened by the widest the left
 * side can reach, so an endpoint the model never stated makes the slack an invention. The row then holds
 * inside the search box only — and at the root the column enters the LP genuinely open, where it holds
 * nowhere.
 *
 * Dropping one row only ever weakens, so the stated side is still emitted when the other is not: on `≤`
 * and `≥` the two rows are the reification's two independent implications, and on `=` and `≠` they are
 * the two halves of a single one, which the survivor then states in one direction alone.
 */
private fun FactorRow.Doubles.emitBigMRows(builder: RelaxationBuilder, row: IntegerRow) {
    var maxStated = true
    var minStated = true
    for (k in intVars.indices) {
        val c = row.coeffs[k]
        if (c == 0L) continue // a zero coefficient reads neither endpoint
        val v = intVars[k]
        maxStated = maxStated && if (c > 0L) builder.statesUpperBound(v) else builder.statesLowerBound(v)
        minStated = minStated && if (c > 0L) builder.statesLowerBound(v) else builder.statesUpperBound(v)
    }
    if (!maxStated && !minStated) return
    // An unstated side is never summed: its endpoint sits where the finite lane clamped the column, and
    // the product there would overflow and take the stated side's row down with it.
    var lMin = 0L
    var lMax = 0L
    var lMinD = 0L
    var lMaxD = 0L
    for (k in intVars.indices) {
        val c = row.coeffs[k]
        val v = intVars[k]
        val dom = builder.liveDomain(v)
        val dec = builder.rootDomain(v)
        if (maxStated) {
            lMax = addExact(lMax, mulExact(c, if (c >= 0L) dom.max else dom.min))
            lMaxD = addExact(lMaxD, mulExact(c, if (c >= 0L) dec.max else dec.min))
        }
        if (minStated) {
            lMin = addExact(lMin, mulExact(c, if (c >= 0L) dom.min else dom.max))
            lMinD = addExact(lMinD, mulExact(c, if (c >= 0L) dec.min else dec.max))
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
            if (maxStated) {
                val m1 = maxOf(0L, subExact(lMax, b))
                emit(m1, LinearOp.LE, addExact(b, m1), m1 == maxOf(0L, subExact(lMaxD, b)), maxSide = true)
            }
            if (minStated) {
                successorOrNull(b)?.let { boundUp ->
                    val m2 = maxOf(0L, subExact(boundUp, lMin))
                    emit(m2, LinearOp.GE, boundUp, m2 == maxOf(0L, subExact(boundUp, lMinD)), maxSide = false)
                }
            }
        }

        LinearOp.GE -> {
            if (minStated) {
                val m1 = maxOf(0L, subExact(b, lMin))
                emit(-m1, LinearOp.GE, subExact(b, m1), m1 == maxOf(0L, subExact(b, lMinD)), maxSide = false)
            }
            if (maxStated) {
                predecessorOrNull(b)?.let { boundDown ->
                    val m2 = maxOf(0L, subExact(lMax, boundDown))
                    emit(-m2, LinearOp.LE, boundDown, m2 == maxOf(0L, subExact(lMaxD, boundDown)), maxSide = true)
                }
            }
        }

        LinearOp.EQ -> {
            if (maxStated) {
                val mHi = maxOf(0L, subExact(lMax, b))
                emit(mHi, LinearOp.LE, addExact(b, mHi), mHi == maxOf(0L, subExact(lMaxD, b)), maxSide = true)
            }
            if (minStated) {
                val mLo = maxOf(0L, subExact(b, lMin))
                emit(-mLo, LinearOp.GE, subExact(b, mLo), mLo == maxOf(0L, subExact(b, lMinD)), maxSide = false)
            }
        }

        LinearOp.NE -> {
            if (maxStated) {
                val mHi = maxOf(0L, subExact(lMax, b))
                emit(-mHi, LinearOp.LE, b, mHi == maxOf(0L, subExact(lMaxD, b)), maxSide = true)
            }
            if (minStated) {
                val mLo = maxOf(0L, subExact(b, lMin))
                emit(mLo, LinearOp.GE, b, mLo == maxOf(0L, subExact(b, lMinD)), maxSide = false)
            }
        }
    }
}

private class IntegerRow(val coeffs: LongArray, val bound: Long)
