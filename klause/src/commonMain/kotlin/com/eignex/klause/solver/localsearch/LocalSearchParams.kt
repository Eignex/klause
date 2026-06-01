package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.SolverParams

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
     * Wall-clock-independent operation budget across all backends. For [LocalSearchSolver]
     * one instruction = one flip (the same unit [maxFlips] counts). When both are set, the
     * smaller wins. `null` = no instruction-budget cap. See [maxFlips] for the LS-specific
     * description; this field exists so callers can use the same budget name across the
     * BacktrackSolver / LocalSearchSolver / BruteForceSolver param objects without
     * remembering which knob each one prefers.
     */
    val maxInstructions: Long? = null,
    val randomSeed: Long? = null,
    /** Variables to pin for the duration of this call. The solver initialises them to
     *  the requested values on every restart and ignores any move that would change
     *  them. Defaults to none. */
    val assumptions: Assumptions = Assumptions.None,
    /** Cooperative cancellation predicate; see [com.eignex.klause.solver.Cancellation]. */
    val cancellation: com.eignex.klause.solver.Cancellation = com.eignex.klause.solver.Cancellation.Never,
    /** How [LocalSearchSolver.minimize] combines constraint violations with the objective
     *  for greedy descent. Defaults to two-phase feasibility-first behaviour; switch to
     *  [CostShaping.linear] or [CostShaping.saturating] on tight problems where the
     *  feasible region is narrow. Ignored by `solve` / `samples` / `enumerate`. */
    val costShaping: CostShaping = CostShaping.FeasibilityFirst,
) : SolverParams {
    override fun withAssumptions(assumptions: Assumptions): LocalSearchParams =
        if (assumptions.isEmpty) this else copy(assumptions = merge(this.assumptions, assumptions))

    override fun withCancellation(cancellation: com.eignex.klause.solver.Cancellation): LocalSearchParams =
        copy(cancellation = cancellation)

    private companion object {
        fun merge(a: Assumptions, b: Assumptions): Assumptions = a.mergedWith(b)
    }
}
