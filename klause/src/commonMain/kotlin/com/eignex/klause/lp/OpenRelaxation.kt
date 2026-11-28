package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.lp.engine.LpBuilder
import com.eignex.klause.lp.engine.Relation
import com.eignex.util.arrays.emptyDoubleArray

// A variable open below enters split as `x = x⁺ − x⁻` (`negCol(v)` is -1 otherwise), never with a huge
// stand-in lower bound: the double view folds each column's lower bound into the row rhs, and at that
// magnitude the fold absorbs the true rhs. Real columns split for the same reason.
internal class OpenRelaxation(
    val builder: LpBuilder,
    val posCol: IntArray,
    val negCol: IntArray,
    val realPos: HashMap<Int, Int>,
    val realNeg: HashMap<Int, Int>,
)

// Columns have zero cost: callers probe one column at a time through objective swaps on the built model.
internal fun openRelaxation(
    bounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    realConstraints: List<Linear> = emptyList(),
    realLower: DoubleArray = emptyDoubleArray(),
    realUpper: DoubleArray = emptyDoubleArray(),
): OpenRelaxation {
    val n = bounds.size
    val builder = LpBuilder()
    val posCol = IntArray(n)
    val negCol = IntArray(n) { -1 }
    for (v in 0 until n) {
        val b = bounds[v]
        if (b.lo != null) {
            posCol[v] = if (b.hi != null) builder.addVar(b.lo, b.hi) else builder.addFreeVar(b.lo, null)
        } else {
            posCol[v] = if (b.hi != null && b.hi >= 0L) builder.addVar(0L, b.hi) else builder.addFreeVar(0L, null)
            negCol[v] = builder.addFreeVar(0L, null)
            if (b.hi != null && b.hi < 0L) {
                // x⁺ is pinned to 0 above; the negative upper bound survives as a row on the pair.
                builder.addRow(intArrayOf(posCol[v], negCol[v]), longArrayOf(1L, -1L), Relation.LE, b.hi)
            }
        }
    }
    for (f in constraints) {
        val rel = relationOfLinear(f) ?: continue
        val row = f.integerConstants ?: continue
        val (cols, vals) = splitTerms(f, posCol, negCol) ?: continue
        builder.addRow(cols, vals, rel, row.bound)
    }
    // A variable defined only through a real row (a floor definition, a to_real bridge) stays reachable.
    // A strict row enters non-strict, which is a sound relaxation.
    val realPos = HashMap<Int, Int>()
    val realNeg = HashMap<Int, Int>()
    for (f in realConstraints) {
        val rel = relationOfLinear(f) ?: continue
        val row = f.realConstants ?: continue
        var extra = 0
        for (k in f.vars.indices) if (negCol[f.vars[k]] >= 0) extra++
        for (j in f.realVars.indices) {
            val rv = f.realVars[j]
            if (rv !in realPos) {
                addRealColumns(builder, rv, realLower, realUpper, realPos, realNeg)
            }
            if (realNeg.containsKey(rv)) extra++
        }
        val cols = IntArray(f.vars.size + f.realVars.size + extra)
        val vals = DoubleArray(cols.size)
        var w = 0
        for (k in f.vars.indices) {
            val v = f.vars[k]
            cols[w] = posCol[v]
            vals[w] = row.intCoefficients.at(k)
            w++
            if (negCol[v] >= 0) {
                cols[w] = negCol[v]
                vals[w] = -row.intCoefficients.at(k)
                w++
            }
        }
        for (j in f.realVars.indices) {
            val rv = f.realVars[j]
            cols[w] = realPos.getValue(rv)
            vals[w] = row.realCoefficients.at(j)
            w++
            val neg = realNeg[rv]
            if (neg != null) {
                cols[w] = neg
                vals[w] = -row.realCoefficients.at(j)
                w++
            }
        }
        builder.addRealRow(cols, vals, rel, row.bound)
    }
    return OpenRelaxation(builder, posCol, negCol, realPos, realNeg)
}
