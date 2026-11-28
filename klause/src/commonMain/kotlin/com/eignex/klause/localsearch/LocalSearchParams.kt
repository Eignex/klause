package com.eignex.klause.localsearch

import com.eignex.klause.backtrack.NodeBudget
import com.eignex.klause.factor.DEFAULT_VIOLATION_SOFT_CAP
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.forInvocation
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.objective.IncrementalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.withLifetime
import com.eignex.klause.util.ExecutionControl

/**
 * Per-call params for [LocalSearchSolver]; strategy and restart policy live on the solver.
 *
 *  - [maxFlips]: deterministic work allowance; every accepted move and restart consumes one unit. Sampling resets
 *    it after each fresh sample, minimization spends it across the call.
 */
data class LocalSearchParams(
    val maxFlips: Long = Long.MAX_VALUE,
    /** Operation budget shared by name across backends: one unit per move or restart; the smaller of this and
     *  [maxFlips] wins. */
    val maxInstructions: Long? = null,
    /**
     * The solve-wide node allowance this run also spends, at [com.eignex.klause.solver.resources.LS_INSTRUCTIONS_PER_WORK]
     * moves per node; the run stops at the move that would pass it. `null` = none.
     */
    val nodeBudget: NodeBudget? = null,
    /** Seed for the search RNG; null picks a nondeterministic seed. */
    val randomSeed: Long? = null,
    /** Variables pinned for this call: set on every restart and never changed by a move. */
    override val assumptions: Assumptions = Assumptions.None,
    /** Cooperative cancellation predicate; see [ExecutionControl]. */
    val cancellation: ExecutionControl = ExecutionControl.Never,
    /** Live-event listener, called inline on the search thread at coarse points such as new incumbents. */
    val onEvent: ((SearchEvent) -> Unit)? = null,
    /** How minimization combines violations with the objective for greedy descent. */
    val costShaping: CostShaping = CostShaping.FeasibilityFirst,
    /**
     * Per-move view of the objective that minimization scores moves against, such as a functionally defined
     * objective whose linear form has no gradient on decision moves. Incumbents are still valued by the
     * [LinearObjective]; only a solver whose per-move invariants hold defined variables in place uses it.
     */
    val lsObjective: IncrementalObjective? = null,
    /** Starting assignment in place of the first random restart; a size-mismatched sample is ignored. */
    val initialAssignment: Sample? = null,
    /**
     * Verified-incumbent exchange shared with peer arms during minimization. Improvements are offered to it, and
     * each restart may anchor on a better installed incumbent; a peer assignment only seeds descent, so it cannot
     * make a result unsound. Importing is skipped under assumption pins a foreign assignment may violate.
     */
    val pooledIncumbents: IncumbentExchange<Sample, Double>? = null,
    /**
     * Per-factor residuals above this grow only logarithmically, so a few large violations cannot starve the many
     * small ones feasibility needs. Lower values approach a violation count.
     */
    val violationSoftCap: Int = DEFAULT_VIOLATION_SOFT_CAP,
    /**
     * Damps each over-represented factor class's initial [FactorWeightBook.factorWeights] so its aggregate weight is
     * capped at the mean class size; weight-blind strategies ignore it.
     */
    val normalizeWeightsByClass: Boolean = false,
    /** Collect graded violations by factor kind at strict committed-cost improvements. */
    val reportResiduals: Boolean = false,
    /** Record active preparation, seeding, selection and application time. */
    val reportPhaseTimes: Boolean = false,
    /** Objective guidance; unsupported geometric cones retain canonical scoring and proposals. */
    val objectiveGuidance: ObjectiveGuidance = ObjectiveGuidance.Functional,
) : SolverParams {
    override fun withAssumptions(assumptions: Assumptions): LocalSearchParams =
        if (assumptions.isEmpty && assumptions.deductions.isEmpty) this
        else copy(assumptions = merge(this.assumptions, assumptions))

    override fun withCancellation(cancellation: ExecutionControl): LocalSearchParams =
        copy(cancellation = this.cancellation.forInvocation(cancellation))

    override fun withLifetimeCancellation(cancellation: ExecutionControl): LocalSearchParams =
        copy(cancellation = this.cancellation.withLifetime(cancellation))

    private companion object {
        fun merge(a: Assumptions, b: Assumptions): Assumptions = a.mergedWith(b)
    }
}
