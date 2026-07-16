package com.eignex.klause.lp

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.solver.Cancellation

/**
 * A variable's integer bounds for [tightenOpenIntBounds]: a `null` side is genuinely ±∞ (open), a `Long`
 * side is a finite bound. A fully-bounded variable has both sides set.
 */
internal class OpenIntBounds(val lo: Long?, val hi: Long?)

/**
 * Optimization-based bound tightening (OBBT): close open (±∞) integer-variable sides to sound finite
 * bounds from the LP relaxation of [constraints]. A variable open above takes the relaxation's maximum
 * of itself as a valid upper bound — the relaxation contains every integer solution — and open below
 * takes its minimum. A side the LP leaves unbounded (its optimum only reaches the free-column frontier)
 * stays open (`null`) for the caller to clamp.
 *
 * The tightening is sound over genuine ±∞ because an open side enters the LP as a real free column
 * ([LpBuilder.addFreeVar]): a derived bound holds over the true unbounded region, not a pre-clamped box.
 * This is why it must run before a [com.eignex.klause.solver.Problem]'s finite
 * [com.eignex.klause.solver.IntDomain]s are committed — once a side is clamped the "genuinely infinite"
 * information is gone. Only [LinearOp.LE]/[LinearOp.GE]/[LinearOp.EQ] constraints enter; any other
 * relation is skipped (dropping a constraint only loosens the relaxation, never unsound). Variables are
 * processed in id order and each closed side feeds the later solves.
 *
 * @param bounds current per-variable bounds, indexed by variable id.
 * @param constraints the linear constraints over those variable ids (an objective is not a constraint;
 *   the caller excludes it).
 * @param cancellation polled between variables so a long presolve can bail early.
 * @return a fresh bounds array with every provable open side closed.
 */
internal fun tightenOpenIntBounds(
    bounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation = Cancellation.Never,
): Array<OpenIntBounds> {
    val work = Array(bounds.size) { bounds[it] }
    for (v in work.indices) {
        if (cancellation()) break
        val cur = work[v]
        if (cur.lo != null && cur.hi != null) continue
        // Both directions read the pre-tightening state of v (independent); v's closed bounds then feed
        // the later variables.
        val newHi = if (cur.hi == null) obbtBound(work, constraints, v, maximize = true, cancellation) else cur.hi
        val newLo = if (cur.lo == null) obbtBound(work, constraints, v, maximize = false, cancellation) else cur.lo
        work[v] = OpenIntBounds(newLo, newHi)
    }
    return work
}

/** A sound finite LP bound on variable [target] (its max when [maximize], else its min) over the
 *  relaxation of [constraints] with the current [work] column bounds, or null when the LP leaves it
 *  unbounded / infeasible / overflows. Each variable is one LP column (a null side is a genuine ±∞ free
 *  column); only [target]'s column carries the objective cost (`−1` maximizing, `+1` minimizing). */
private fun obbtBound(
    work: Array<OpenIntBounds>,
    constraints: List<Linear>,
    target: Int,
    maximize: Boolean,
    cancellation: Cancellation,
): Long? {
    val builder = LpBuilder()
    val n = work.size
    val col = IntArray(n)
    for (v in 0 until n) {
        val cost = if (v == target) (if (maximize) -1L else 1L) else 0L
        val l = work[v].lo
        val h = work[v].hi
        col[v] = if (l != null && h != null) builder.addVar(l, h, cost) else builder.addFreeVar(l, h, cost)
    }
    for (f in constraints) {
        val rel = when (f.op) {
            LinearOp.LE -> Relation.LE
            LinearOp.GE -> Relation.GE
            LinearOp.EQ -> Relation.EQ
            else -> continue
        }
        val cols = IntArray(f.vars.size) { col[f.vars[it]] }
        builder.addRow(cols, f.coeffs.copyOf(), rel, f.bound)
    }
    val model = try {
        builder.build(Sense.MINIMIZE)
    } catch (_: LpOverflowException) {
        return null
    }
    val result = try {
        newLpSolver(model, cancellation).solvePrimal()
    } catch (_: LpOverflowException) {
        return null
    } ?: return null
    return model.tightVariableBound(result, col[target], maximize)
}
