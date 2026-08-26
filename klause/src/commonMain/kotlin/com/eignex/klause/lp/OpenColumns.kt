package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp

/**
 * The split column pair per variable, plus the builder they were added to.
 *
 * A variable with no finite bound cannot enter a bounded-variable simplex as one column, and giving it a
 * huge stand-in bound is worse than useless: at that magnitude a double's spacing swamps the right-hand
 * sides, which contaminates every component of `B⁻¹`. Each variable therefore enters split,
 * `x = x⁺ − x⁻`, over two non-negative columns ([openColumns]), and a row's terms are rewritten onto that
 * pair ([splitTerms]).
 *
 * Shared by the passes that reason over open ranges — the cube witness ([unitCubeSolution]) and the
 * Farkas refutation ([openLpInfeasible]) — so both build the same columns from the same declared bounds.
 */
internal class OpenColumns(val builder: LpBuilder, val posCol: IntArray, val negCol: IntArray)

internal fun builderOf(): LpBuilder = LpBuilder()

/**
 * Add one column per variable over its genuinely open range: a variable open below enters split as
 * `x = x⁺ − x⁻` with both parts non-negative, never as a probe-magnitude lower bound — the double view
 * folds a column's lower bound into the row rhs, and at that magnitude the fold absorbs the true rhs.
 */
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

/** The builder relation for a linear row, or null for an operator the relaxation cannot express. */
internal fun relationOfLinear(f: Linear): Relation? = when (f.op) {
    LinearOp.LE -> Relation.LE
    LinearOp.GE -> Relation.GE
    LinearOp.EQ -> Relation.EQ
    else -> null
}

/** Row terms over the split column pairs: a variable represented `x = x⁺ − x⁻` contributes both halves.
 *  Only a 64-bit integer row has terms this builds. */
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
