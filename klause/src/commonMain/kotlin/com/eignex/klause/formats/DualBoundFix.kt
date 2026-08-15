package com.eignex.klause.formats

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.lp.OpenIntBounds
import com.eignex.klause.solver.Factor

/**
 * Close an open side that the **objective** makes pointless to explore.
 *
 * A column open above whose cost rises with it, and which no constraint ever needs larger, has an optimal
 * solution at its lower bound: from any solution, walking it down preserves feasibility and cannot raise
 * the objective. The side closes on the model's own terms, so the box that results is not an invented
 * search window and a verdict over it carries no clamp caveat.
 *
 * This is what a feasibility-only bound cannot reach. The relaxation of a lot-sizing MIP is genuinely
 * unbounded in these directions — nothing but the objective stops a batch count growing — so OBBT reports
 * no bound however long it runs, and the fallback box has to be invented instead.
 *
 * Conservative by construction. A column is left alone unless *every* factor mentioning it is an
 * inequality whose coefficient points the safe way: an equality pins it, a reified row only constrains it
 * on one branch, and an unrecognised factor could need it anywhere. Declining costs a clamp; a wrong
 * closure costs an optimum.
 */
internal fun dualFixableBounds(
    numInt: Int,
    factors: List<Factor>,
    open: Array<OpenIntBounds>,
    minimiseCost: (Int) -> Long,
): Array<OpenIntBounds> {
    if (numInt == 0) return open
    // Per column: whether every inequality coefficient seen so far is non-negative / non-positive, and
    // whether anything disqualifies it outright.
    val allNonNegative = BooleanArray(numInt) { true }
    val allNonPositive = BooleanArray(numInt) { true }
    val disqualified = BooleanArray(numInt)
    for (f in factors) {
        when {
            f is Linear && f.op == LinearOp.LE -> recordSigns(f, numInt, allNonNegative, allNonPositive)
            f is ReifiedLinear -> for (v in f.vars) if (v < numInt) disqualified[v] = true
            else -> for (v in f.intVars) if (v < numInt) disqualified[v] = true
        }
    }
    var changed = false
    val out = Array(numInt) { v ->
        val b = open[v]
        val cost = minimiseCost(v)
        when {
            disqualified[v] -> b

            // Rising cost and never needed larger: an optimum sits at the lower bound.
            b.hi == null && b.lo != null && cost > 0L && allNonNegative[v] -> {
                changed = true
                OpenIntBounds(b.lo, b.lo)
            }

            // The mirror: falling cost and never needed smaller pins it at the upper bound.
            b.lo == null && b.hi != null && cost < 0L && allNonPositive[v] -> {
                changed = true
                OpenIntBounds(b.hi, b.hi)
            }

            else -> b
        }
    }
    return if (changed) out else open
}

/** Fold one `≤` row's coefficient signs into the per-column flags. */
private fun recordSigns(f: Linear, numInt: Int, allNonNegative: BooleanArray, allNonPositive: BooleanArray) {
    val wide = f.wideCoeffs
    val mixed = if (f.hasReals) f.realIntCoeffs else null
    for (k in f.vars.indices) {
        val v = f.vars[k]
        if (v >= numInt) continue
        val negative: Boolean
        val positive: Boolean
        when {
            wide != null -> {
                negative = wide[k].signum() < 0
                positive = wide[k].signum() > 0
            }

            mixed != null -> {
                negative = mixed[k] < 0.0
                positive = mixed[k] > 0.0
            }

            else -> {
                negative = f.coeff(k) < 0L
                positive = f.coeff(k) > 0L
            }
        }
        if (negative) allNonNegative[v] = false
        if (positive) allNonPositive[v] = false
    }
}
