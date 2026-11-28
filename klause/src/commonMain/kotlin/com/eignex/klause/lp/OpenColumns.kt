package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation

// `negCol(v)` is -1 unless the variable enters split as `x = x⁺ − x⁻`. Shared by the passes that reason
// over open ranges, so relaxation and Farkas checks build the same columns from the same declared bounds.
internal class OpenColumns(val builder: LpBuilder, val posCol: IntArray, val negCol: IntArray)

internal fun builderOf(): LpBuilder = LpBuilder()

// A variable open below enters split over two non-negative columns, never with a huge stand-in lower bound:
// the double view folds a column's lower bound into the row rhs, and at that magnitude the fold absorbs the
// true rhs.
internal fun openColumns(builder: LpBuilder, bounds: Array<OpenIntBounds>): OpenColumns {
    val n = bounds.size
    val posCol = IntArray(n)
    val negCol = IntArray(n) { -1 }
    for (v in 0 until n) {
        val b = bounds[v]
        if (b.lo != null) {
            // A hi-open column carries no upper at all rather than the probe stand-in: a Farkas
            // certificate that reads a probe bound proves nothing about the unbounded model.
            posCol[v] = if (b.hi != null) builder.addVar(b.lo, b.hi) else builder.addOpenAboveVar(b.lo)
        } else {
            posCol[v] = if (b.hi != null && b.hi >= 0L) builder.addVar(0L, b.hi) else builder.addFreeVar(0L, null)
            negCol[v] = builder.addFreeVar(0L, null)
            if (b.hi != null && b.hi < 0L) {
                builder.addRow(intArrayOf(posCol[v], negCol[v]), longArrayOf(1L, -1L), Relation.LE, b.hi)
            }
        }
    }
    return OpenColumns(builder, posCol, negCol)
}

internal fun relationOfLinear(f: Linear): Relation? = when (f.op) {
    LinearOp.LE -> Relation.LE
    LinearOp.GE -> Relation.GE
    LinearOp.EQ -> Relation.EQ
    else -> null
}

// Null for a row without 64-bit integer coefficients.
internal fun splitTerms(f: Linear, posCol: IntArray, negCol: IntArray): Pair<IntArray, LongArray>? {
    val row = f.integerConstants ?: return null
    var extra = 0
    for (k in f.vars.indices) if (negCol[f.vars[k]] >= 0) extra++
    val cols = IntArray(f.vars.size + extra)
    val vals = LongArray(cols.size)
    var w = 0
    for (k in f.vars.indices) {
        val v = f.vars[k]
        cols[w] = posCol[v]
        vals[w] = row.coeff(k)
        w++
        if (negCol[v] >= 0) {
            cols[w] = negCol[v]
            vals[w] = -row.coeff(k)
            w++
        }
    }
    return cols to vals
}

private val EmptyIntArrayLocal = IntArray(0)

internal fun addRealColumns(
    builder: LpBuilder,
    rv: Int,
    realLower: DoubleArray,
    realUpper: DoubleArray,
    realPos: HashMap<Int, Int>,
    realNeg: HashMap<Int, Int>,
) {
    val lo = realLower.getOrNull(rv)?.takeIf { it.isFinite() }
    val hi = realUpper.getOrNull(rv)?.takeIf { it.isFinite() }
    if (lo != null) {
        realPos[rv] = builder.addRealVar(lo, hi)
        return
    }
    val pos = if (hi != null && hi >= 0.0) builder.addRealVar(0.0, hi) else builder.addRealVar(0.0, null)
    val neg = builder.addRealVar(0.0, null)
    realPos[rv] = pos
    realNeg[rv] = neg
    if (hi != null && hi < 0.0) {
        // The positive half is pinned to 0 above; the finite negative upper bound survives as a row.
        builder.addRealRow(intArrayOf(pos, neg), doubleArrayOf(1.0, -1.0), Relation.LE, hi)
    }
}
