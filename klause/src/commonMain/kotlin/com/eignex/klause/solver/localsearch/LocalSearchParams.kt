package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.factor.DEFAULT_VIOLATION_SOFT_CAP
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent

/**
 * Per-call params for the local-search [LocalSearchSolver]. Engine setup
 * ([LocalSearchSolver.strategy], [LocalSearchSolver.restartPolicy]) lives on the
 * constructor; this data class carries the knobs that vary per `sample` / `enumerate` /
 * `solve` call.
 *
 *  - [maxFlips] — flip budget *per yield attempt*. After this many flips elapse without
 *    producing a fresh sample, the sequence ends. Counter resets on every yield. Leave at
 *    [Long.MAX_VALUE] to never give up; lower it to make `enumerate` short-circuit when
 *    the engine has effectively exhausted the local solution space.
 */
data class LocalSearchParams(
    val maxFlips: Long = Long.MAX_VALUE,
    /**
     * Wall-clock-independent operation budget across all backends. For [LocalSearchSolver] one
     * instruction = one flip (the unit [maxFlips] counts); when both are set, the smaller wins.
     * `null` = no cap. Exists so callers can use one budget name across the backends' param objects.
     */
    val maxInstructions: Long? = null,
    /** Seed for the search RNG; null picks a nondeterministic seed. */
    val randomSeed: Long? = null,
    /** Variables to pin for the duration of this call. The solver initialises them to
     *  the requested values on every restart and ignores any move that would change
     *  them. Defaults to none. */
    val assumptions: Assumptions = Assumptions.None,
    /** Cooperative cancellation predicate; see [Cancellation]. */
    val cancellation: Cancellation = Cancellation.Never,
    /**
     * Optional live-event listener; see [SearchEvent]. Called
     * inline on the search thread at coarse points only (restarts, learned-DB sweeps,
     * incumbents). `null` (default) disables observation entirely.
     */
    val onEvent: ((SearchEvent) -> Unit)? = null,
    /** How [LocalSearchSolver.minimize] combines constraint violations with the objective
     *  for greedy descent. Defaults to two-phase feasibility-first behaviour; switch to
     *  [CostShaping.linear] or [CostShaping.saturating] on tight problems where the
     *  feasible region is narrow. Ignored by `solve` / `samples` / `enumerate`. */
    val costShaping: CostShaping = CostShaping.FeasibilityFirst,
    /**
     * Optional per-move *gradient view* of the objective for [LocalSearchSolver.minimize] /
     * `improvements`: when non-null, the descent scores and evaluates against this
     * [com.eignex.klause.solver.objective.IncrementalObjective] instead of the [LinearObjective]
     * passed to `minimize`. The canonical use is a functionally-defined objective whose linear form
     * has zero gradient on decision moves; this view recomputes the defined var from the leaves,
     * giving CBLS the gradient that matters (see
     * [com.eignex.klause.solver.objective.FunctionalObjective]). Must agree with the linear objective
     * on every *feasible* assignment so incumbents stay comparable across engines. `null` (default)
     * descends the linear objective directly. Ignored by `solve` / `samples` / `enumerate`.
     */
    val lsObjective: IncrementalObjective? = null,
    /**
     * Optional warm-start *assignment* for [LocalSearchSolver.minimize] / `improvements`: when
     * non-null, the descent begins from this assignment instead of a random restart, then optimises
     * from there. Intended for a hybrid pipeline handing a CP/backtrack-found feasible point to LS.
     * Size-mismatched samples are ignored. `null` (default) keeps pure local search free of any CP
     * dependency. Ignored by `solve` / `samples` / `enumerate`.
     */
    val initialAssignment: Sample? = null,
    /**
     * Soft cap for the graded violation cost (see
     * `compressViolation`). Per-factor residuals at or below this
     * contribute their exact magnitude; above it they grow only logarithmically, so a handful of
     * large-magnitude constraints (a wide `int_lin_eq`, a deep cumulative overload) can't dominate
     * the cost sum and starve the many small violations feasibility needs. Lower it toward `0` for
     * a near-pure "count of violations" cost; raise it for raw-magnitude descent. Defaults to
     * [com.eignex.klause.solver.factor.DEFAULT_VIOLATION_SOFT_CAP]; shared by every factor in the solve.
     */
    val violationSoftCap: Int = DEFAULT_VIOLATION_SOFT_CAP,
    /**
     * Seed the weighted-violation strategies' initial [LocalSearchState.factorWeights] so no single
     * constraint *kind* dominates the landscape by population. When set, an over-represented factor
     * class (count above the mean class size) is damped so its aggregate initial weight is capped at
     * that mean — purely monotone (it only lowers weights). Weight-blind strategies (WalkSat /
     * ProbSat / SA) never read the weights, so this is a no-op for them.
     */
    val normalizeWeightsByClass: Boolean = false,
) : SolverParams {
    override fun withAssumptions(assumptions: Assumptions): LocalSearchParams =
        if (assumptions.isEmpty) this else copy(assumptions = merge(this.assumptions, assumptions))

    override fun withCancellation(cancellation: Cancellation): LocalSearchParams = copy(cancellation = cancellation)

    private companion object {
        fun merge(a: Assumptions, b: Assumptions): Assumptions = a.mergedWith(b)
    }
}
