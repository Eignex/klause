package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.util.EmptyDoubleArray

/**
 * The LP relaxation of a system over its **genuinely open** integer ranges, shared by everything that has
 * to reason about a model outside the box the search is given: the bound tightening
 * ([tightenOpenIntBounds]), and the dual bound that certifies an in-box optimum global
 * ([DeferredIntBounds.noWorseThan]).
 *
 * A variable open below enters split, `x = x⁺ − x⁻` with both parts non-negative, never as a probe-
 * magnitude lower bound: the double view folds each column's lower bound into the row rhs in doubles, and
 * at the probe's magnitude that fold absorbs the true rhs — every row would degenerate and every derived
 * bound be void at the frontier. Splitting keeps the fold at zero. Real columns split for the same reason.
 */
internal class OpenRelaxation(
    val builder: LpBuilder,
    /** Per integer variable, its `x⁺` column. */
    val posCol: IntArray,
    /** Per integer variable, its `x⁻` column, or `-1` when the variable is bounded below and needs none. */
    val negCol: IntArray,
    /** Per real variable that appears in a row, its positive column. */
    val realPos: HashMap<Int, Int>,
    /** Per real variable open below, its negative column. */
    val realNeg: HashMap<Int, Int>,
)

/**
 * Build the relaxation of [constraints] (and the real-bearing [realConstraints]) over [bounds].
 *
 * [intCost] and [realCost] attach an objective as the columns are created, which is the only point at
 * which a *real* cost can be set — the in-place objective swaps on a built model carry integer costs
 * only. A variable represented `x = x⁺ − x⁻` gets `+c` on its positive column and `−c` on its negative,
 * so the objective is exactly `c·x` either way. Null leaves every cost zero, the shape the per-column
 * bound probes want.
 */
@Suppress("LongParameterList")
internal fun openRelaxation(
    bounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    realConstraints: List<Linear> = emptyList(),
    realLower: DoubleArray = EmptyDoubleArray,
    realUpper: DoubleArray = EmptyDoubleArray,
    intCost: LongArray? = null,
    realCost: DoubleArray? = null,
): OpenRelaxation {
    val n = bounds.size
    val builder = LpBuilder()
    val posCol = IntArray(n)
    val negCol = IntArray(n) { -1 }
    // A split column needs `−c` as well as `c`, so a cost that cannot be negated leaves the objective off
    // entirely rather than silently wrong on one half of a pair.
    val costs = intCost?.takeIf { c -> c.none { it == Long.MIN_VALUE } }
    for (v in 0 until n) {
        val b = bounds[v]
        val c = costs?.getOrNull(v) ?: 0L
        if (b.lo != null) {
            posCol[v] = if (b.hi != null) builder.addVar(b.lo, b.hi, c) else builder.addFreeVar(b.lo, null, c)
        } else {
            posCol[v] = if (b.hi != null && b.hi >= 0L) {
                builder.addVar(0L, b.hi, c)
            } else {
                builder.addFreeVar(0L, null, c)
            }
            negCol[v] = builder.addFreeVar(0L, null, -c)
            if (b.hi != null && b.hi < 0L) {
                // x⁺ is pinned to 0 above; the finite negative upper bound survives as a row on the pair.
                builder.addRow(intArrayOf(posCol[v], negCol[v]), longArrayOf(1L, -1L), Relation.LE, b.hi)
            }
        }
    }
    for (f in constraints) {
        val rel = relationOfRow(f) ?: continue
        var extra = 0
        for (k in f.vars.indices) if (negCol[f.vars[k]] >= 0) extra++
        val cols = IntArray(f.vars.size + extra)
        val vals = LongArray(cols.size)
        var w = 0
        for (k in f.vars.indices) {
            val v = f.vars[k]
            cols[w] = posCol[v]
            vals[w] = f.coeff(k)
            w++
            if (negCol[v] >= 0) {
                cols[w] = negCol[v]
                vals[w] = -f.coeff(k)
                w++
            }
        }
        builder.addRow(cols, vals, rel, f.bound)
    }
    // Mixed integer/real rows join through LP-only continuous columns, so a variable whose only definition
    // rides a real row (a floor definition, a to_real bridge) is still reachable. A strict row enters
    // non-strict — a relaxation, so every bound and every dual bound derived here stays sound.
    val realPos = HashMap<Int, Int>()
    val realNeg = HashMap<Int, Int>()
    for (f in realConstraints) {
        val rel = relationOfRow(f) ?: continue
        var extra = 0
        for (k in f.vars.indices) if (negCol[f.vars[k]] >= 0) extra++
        for (j in f.realVars.indices) {
            val rv = f.realVars[j]
            if (rv !in realPos) {
                addRealRelaxationColumns(builder, rv, realLower, realUpper, realPos, realNeg, realCost)
            }
            if (realNeg.containsKey(rv)) extra++
        }
        val cols = IntArray(f.vars.size + f.realVars.size + extra)
        val vals = DoubleArray(cols.size)
        var w = 0
        for (k in f.vars.indices) {
            val v = f.vars[k]
            cols[w] = posCol[v]
            vals[w] = f.realIntCoeffs[k]
            w++
            if (negCol[v] >= 0) {
                cols[w] = negCol[v]
                vals[w] = -f.realIntCoeffs[k]
                w++
            }
        }
        for (j in f.realVars.indices) {
            val rv = f.realVars[j]
            cols[w] = realPos.getValue(rv)
            vals[w] = f.realCoeffs[j]
            w++
            val neg = realNeg[rv]
            if (neg != null) {
                cols[w] = neg
                vals[w] = -f.realCoeffs[j]
                w++
            }
        }
        builder.addRealRow(cols, vals, rel, f.realBound)
    }
    return OpenRelaxation(builder, posCol, negCol, realPos, realNeg)
}

/** A real variable's column, split into a non-negative pair when it is open below. */
private fun addRealRelaxationColumns(
    builder: LpBuilder,
    rv: Int,
    realLower: DoubleArray,
    realUpper: DoubleArray,
    realPos: HashMap<Int, Int>,
    realNeg: HashMap<Int, Int>,
    realCost: DoubleArray?,
) {
    val cost = realCost?.getOrNull(rv)?.takeIf { it.isFinite() } ?: 0.0
    val lo = realLower.getOrNull(rv)?.takeIf { it.isFinite() }
    val hi = realUpper.getOrNull(rv)?.takeIf { it.isFinite() }
    if (lo != null) {
        realPos[rv] = builder.addRealVar(lo, hi, cost)
        return
    }
    val pos = if (hi != null && hi >= 0.0) {
        builder.addRealVar(0.0, hi, cost)
    } else {
        builder.addRealVar(0.0, null, cost)
    }
    val neg = builder.addRealVar(0.0, null, -cost)
    realPos[rv] = pos
    realNeg[rv] = neg
    if (hi != null && hi < 0.0) {
        // The positive half is pinned to 0 above; the finite negative upper bound survives as a row.
        builder.addRealRow(intArrayOf(pos, neg), doubleArrayOf(1.0, -1.0), Relation.LE, hi)
    }
}

/** The builder relation of a linear row, or null for an operator the relaxation cannot express. */
private fun relationOfRow(f: Linear): Relation? = when (f.op) {
    LinearOp.LE -> Relation.LE
    LinearOp.GE -> Relation.GE
    LinearOp.EQ -> Relation.EQ
    else -> null
}
