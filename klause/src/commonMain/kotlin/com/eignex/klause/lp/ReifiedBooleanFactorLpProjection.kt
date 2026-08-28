package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.addExact
import com.eignex.klause.util.subExact

internal fun ReifiedCardinality.emitLpRelaxation(builder: RelaxationBuilder) {
    val sum = BoolReifiedSum.fold(builder, literals, weights = null)
    val a = builder.boolColumn(auxBoolVar)
    val lo = subExact(min.toLong(), sum.constant)
    val hi = subExact(max.toLong(), sum.constant)
    val mHi = maxOf(0L, subExact(sum.lMax, hi))
    sum.reifiedRow(builder, a, mHi, LinearOp.LE, addExact(hi, mHi))
    val mLo = maxOf(0L, subExact(lo, sum.lMin))
    sum.reifiedRow(builder, a, -mLo, LinearOp.GE, subExact(lo, mLo))
}

internal fun ReifiedPseudoBoolean.emitLpRelaxation(builder: RelaxationBuilder) {
    val sum = BoolReifiedSum.fold(builder, literals, weights)
    val a = builder.boolColumn(auxBoolVar)
    val b = subExact(bound, sum.constant)
    when (op) {
        PbOp.LE -> {
            val m1 = maxOf(0L, subExact(sum.lMax, b))
            sum.reifiedRow(builder, a, m1, LinearOp.LE, addExact(b, m1))
            if (b != Long.MAX_VALUE) {
                val falseBound = addExact(b, 1L)
                val m2 = maxOf(0L, subExact(falseBound, sum.lMin))
                sum.reifiedRow(builder, a, m2, LinearOp.GE, falseBound)
            }
        }

        PbOp.GE -> {
            val m1 = maxOf(0L, subExact(b, sum.lMin))
            sum.reifiedRow(builder, a, -m1, LinearOp.GE, subExact(b, m1))
            if (b != Long.MIN_VALUE) {
                val falseBound = subExact(b, 1L)
                val m2 = maxOf(0L, subExact(sum.lMax, falseBound))
                sum.reifiedRow(builder, a, -m2, LinearOp.LE, falseBound)
            }
        }

        PbOp.EQ -> {
            val mHi = maxOf(0L, subExact(sum.lMax, b))
            sum.reifiedRow(builder, a, mHi, LinearOp.LE, addExact(b, mHi))
            val mLo = maxOf(0L, subExact(b, sum.lMin))
            sum.reifiedRow(builder, a, -mLo, LinearOp.GE, subExact(b, mLo))
        }
    }
}

private class BoolReifiedSum private constructor(
    private val cols: IntArray,
    private val coeffs: LongArray,
    val constant: Long,
    val lMin: Long,
    val lMax: Long,
) {
    fun reifiedRow(builder: RelaxationBuilder, auxCol: Int, auxCoeff: Long, op: LinearOp, rhs: Long) {
        val rowCols = cols.copyOf(cols.size + 1)
        val rowVals = coeffs.copyOf(coeffs.size + 1)
        rowCols[cols.size] = auxCol
        rowVals[coeffs.size] = auxCoeff
        builder.row(rowCols, rowVals, op, rhs)
    }

    companion object {
        fun fold(builder: RelaxationBuilder, literals: IntArray, weights: LongArray?): BoolReifiedSum {
            val coeffByCol = LinkedHashMap<Int, Long>()
            var constant = 0L
            for (k in literals.indices) {
                val lit = literals[k]
                val w = weights?.get(k) ?: 1L
                val col = builder.boolColumn(Lit.variable(lit))
                val c = if (Lit.isPositive(lit)) w else -w
                if (!Lit.isPositive(lit)) constant = addExact(constant, w)
                coeffByCol[col] = addExact(coeffByCol.getOrElse(col) { 0L }, c)
            }
            val cols = IntArray(coeffByCol.size)
            val coeffs = LongArray(coeffByCol.size)
            var lMin = 0L
            var lMax = 0L
            var i = 0
            for ((col, c) in coeffByCol) {
                cols[i] = col
                coeffs[i] = c
                i++
                if (c >= 0L) lMax = addExact(lMax, c) else lMin = addExact(lMin, c)
            }
            return BoolReifiedSum(cols, coeffs, constant, lMin, lMax)
        }
    }
}
