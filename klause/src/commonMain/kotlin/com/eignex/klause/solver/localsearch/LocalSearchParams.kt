package com.eignex.klause.solver.localsearch

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Sample
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
    /** Seed for the search RNG; null picks a nondeterministic seed. */
    val randomSeed: Long? = null,
    /** Variables to pin for the duration of this call. The solver initialises them to
     *  the requested values on every restart and ignores any move that would change
     *  them. Defaults to none. */
    val assumptions: Assumptions = Assumptions.None,
    /** Cooperative cancellation predicate; see [Cancellation]. */
    val cancellation: Cancellation = Cancellation.Never,
    /**
     * Optional live-event listener; see [com.eignex.klause.solver.SearchEvent]. Called
     * inline on the search thread at coarse points only (restarts, learned-DB sweeps,
     * incumbents). `null` (default) disables observation entirely.
     */
    val onEvent: ((com.eignex.klause.solver.SearchEvent) -> Unit)? = null,
    /** How [LocalSearchSolver.minimize] combines constraint violations with the objective
     *  for greedy descent. Defaults to two-phase feasibility-first behaviour; switch to
     *  [CostShaping.linear] or [CostShaping.saturating] on tight problems where the
     *  feasible region is narrow. Ignored by `solve` / `samples` / `enumerate`. */
    val costShaping: CostShaping = CostShaping.FeasibilityFirst,
    /**
     * Optional warm-start *assignment* for [LocalSearchSolver.minimize] / `improvements`: when
     * non-null, the descent begins from this assignment instead of a random restart, then
     * optimises the objective from there. Intended for a hybrid pipeline that hands a feasible
     * point found by the CP/backtrack solver to LS (the #54 misses reach feasibility trivially
     * under CP but never under LS). Size-mismatched samples are ignored.
     *
     * Defaults to `null`: the pure-LS entry point ([com.eignex.klause] FZN CLI
     * `runWithLocalSearch`) never sets it, keeping pure local search free of any CP dependency.
     * Only the bench / hybrid driver populates it, behind an explicit opt-in. Ignored by
     * `solve` / `samples` / `enumerate`.
     */
    val initialAssignment: Sample? = null,
) : SolverParams {
    override fun withAssumptions(assumptions: Assumptions): LocalSearchParams =
        if (assumptions.isEmpty) this else copy(assumptions = merge(this.assumptions, assumptions))

    override fun withCancellation(cancellation: Cancellation): LocalSearchParams = copy(cancellation = cancellation)

    private companion object {
        fun merge(a: Assumptions, b: Assumptions): Assumptions = a.mergedWith(b)
    }
}
