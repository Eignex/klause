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
 * relation is skipped (dropping a constraint only loosens the relaxation, never unsound).
 *
 * The relaxation is built **once** and every open side is a re-solve of that one model with a single ±1
 * cost swapped onto its column ([LpModel.withSingleColumnObjective]) — the matrix, rows and bounds never
 * change, so the previous optimal basis stays primal-feasible and the primal simplex warm-starts from it
 * ([LpSolver.solvePrimal]) in a few pivots rather than refactorizing a freshly-built model per side. Each
 * side's bound is derived over the original (un-tightened) bounds, so — unlike a sequential pass that
 * feeds each closed side into later solves — a bound is never sharpened by an earlier one; every bound is
 * still individually sound (the relaxation contains every solution), only potentially looser, which never
 * removes a feasible point.
 *
 * @param bounds current per-variable bounds, indexed by variable id.
 * @param constraints the linear constraints over those variable ids (an objective is not a constraint;
 *   the caller excludes it).
 * @param cancellation polled between variables so a long presolve can bail early — also threaded into each
 *   solve, so a single overlong LP re-solve is cut off too.
 * @return a fresh bounds array with every provable open side closed.
 */
internal fun tightenOpenIntBounds(
    bounds: Array<OpenIntBounds>,
    constraints: List<Linear>,
    cancellation: Cancellation = Cancellation.Never,
): Array<OpenIntBounds> {
    val n = bounds.size
    val work = Array(n) { bounds[it] }
    if (work.none { it.lo == null || it.hi == null }) return work // nothing open to tighten

    // Column j is variable j (added in id order); a genuine open side is a free column. Objective is zero
    // — each solve swaps in its own single-column cost.
    val builder = LpBuilder()
    for (v in 0 until n) {
        val b = work[v]
        if (b.lo != null && b.hi != null) builder.addVar(b.lo, b.hi) else builder.addFreeVar(b.lo, b.hi)
    }
    for (f in constraints) {
        val rel = when (f.op) {
            LinearOp.LE -> Relation.LE
            LinearOp.GE -> Relation.GE
            LinearOp.EQ -> Relation.EQ
            else -> continue
        }
        builder.addRow(IntArray(f.vars.size) { f.vars[it] }, f.coeffs.copyOf(), rel, f.bound)
    }
    val base = try {
        builder.build(Sense.MINIMIZE)
    } catch (_: LpOverflowException) {
        return work // cannot relax; leave every open side to the caller's clamp
    }

    var warm: Basis? = null
    var prevCol = -1
    for (v in 0 until n) {
        if (cancellation()) break
        val cur = work[v]
        if (cur.lo != null && cur.hi != null) continue
        var newHi = cur.hi
        var newLo = cur.lo
        // maximize x_v bounds the open upper side; minimize bounds the open lower side.
        for (maximize in booleanArrayOf(true, false)) {
            if (if (maximize) cur.hi != null else cur.lo != null) continue
            val model = base.withSingleColumnObjective(v, if (maximize) -1L else 1L, prevCol)
            prevCol = v
            val result = try {
                newLpSolver(model, cancellation).solvePrimal(warm)
            } catch (_: LpOverflowException) {
                null
            }
            if (result != null) {
                warm = result.basis
                val bound = model.tightVariableBound(result, v, maximize)
                if (maximize) newHi = bound else newLo = bound
            }
        }
        work[v] = OpenIntBounds(newLo, newHi)
    }
    return work
}
