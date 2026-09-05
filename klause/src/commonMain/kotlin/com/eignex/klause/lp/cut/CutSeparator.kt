package com.eignex.klause.lp.cut

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.Cut
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.lp.statesBothBounds
import com.eignex.klause.lp.statesLowerBound
import com.eignex.klause.lp.statesUpperBound
import com.eignex.klause.propagation.PropagationSession

/** Everything a separator needs: the problem, the current relaxation, its LP solution, the session. */
internal class CutContext(
    val problem: Problem,
    val relaxation: LpRelaxation,
    /** Per-structural-column LP primal value (`RevisedSimplex.FloatLpResult.primal`); the point to separate. */
    val primal: DoubleArray,
    val session: PropagationSession,
) {
    /** The LP primal value of structural column [col] (0 outside the primal vector, e.g. an unmapped column). */
    fun primalOf(col: Int): Double = if (col in primal.indices) primal[col] else 0.0

    /** The root (node-invariant) domain of integer variable [v] — the reference a separator compares
     *  [session]'s live domain against; see [com.eignex.klause.lp.rootDomainOf]. */
    fun rootDomain(v: Int): IntDomain = problem.rootDomainOf(v)

    /** Whether the model itself bounds integer variable [v] below, so a cut whose constants read that
     *  root endpoint may be published as globally valid; see [com.eignex.klause.lp.statesLowerBound]. */
    fun statesLowerBound(v: Int): Boolean = problem.statesLowerBound(v)

    /** Whether the model itself bounds integer variable [v] above; see [statesLowerBound]. */
    fun statesUpperBound(v: Int): Boolean = problem.statesUpperBound(v)

    /** Whether both of [v]'s root endpoints are the model's own; see [statesLowerBound]. */
    fun statesBothBounds(v: Int): Boolean = problem.statesBothBounds(v)
}

/**
 * Separates violated cuts from a fractional LP solution. Implementations inspect the LP point
 * and the problem structure and return cuts the point violates. Returning only violated cuts (rather
 * than all valid ones) keeps the LP from growing with constraints it does not need.
 */
internal interface CutSeparator {
    fun separate(ctx: CutContext): List<Cut>
}

/**
 * True when every [vars] member's live `[min, max]` equals the interval the model declares — a bound
 * derived from the live intervals is then valid at every solution, not only inside the node's box.
 *
 * A column whose root box closed a side the model left open never qualifies: its live interval matches
 * that box at the root, but the box bounds the search rather than the model, so a bound leaning on its
 * endpoint is exactly what may not be published as global.
 */
internal fun liveIntervalsAreDeclared(ctx: CutContext, vars: IntArray): Boolean {
    for (v in vars) {
        if (!ctx.statesBothBounds(v)) return false
        val live = ctx.session.intDomain(v)
        val root = ctx.rootDomain(v)
        if (live.min != root.min || live.max != root.max) return false
    }
    return true
}

/** Hole-aware version of [liveIntervalsAreDeclared]: the live domain is always a subset of the root
 *  one, so equal sizes mean equal value sets. */
internal fun liveDomainsAreDeclared(ctx: CutContext, vars: IntArray): Boolean {
    for (v in vars) {
        if (!ctx.statesBothBounds(v)) return false
        if (ctx.session.intDomain(v).valueCount != ctx.rootDomain(v).valueCount) return false
    }
    return true
}
