package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.SolverParams

/**
 * Per-call params for [BacktrackSolver].
 *
 *  - [maxDecisions] — abort after this many decisions are pushed (Unknown). `Long.MAX_VALUE`
 *    by default — let the search run to completion.
 *  - [randomSeed] — seeds the engine RNG that's threaded into [variableHeuristic] and
 *    [valueHeuristic]. `null` picks a fresh seed per call.
 *  - [assumptions] — variables pinned for the duration of the call.
 *  - [variableHeuristic] — picks the next variable to branch on. Defaults to
 *    [RandomVariable] for diverse search; CSP-typical alternatives are [SmallestDomain]
 *    (first-fail) and [InputOrder].
 *  - [valueHeuristic] — picks the order in which to try values of the chosen variable.
 *    Defaults to [IndomainRandom]; alternatives include [IndomainMin] / [IndomainMax] /
 *    [IndomainMiddle] / [IndomainSet] for hole domains.
 *  - [minHammingDistance] / [recentWindow] — opt-in diversity filter for the
 *    [BacktrackSolver.enumerate] path; only set them when you want to *skip* models that
 *    are within `minHammingDistance` of a previously yielded model. The DFS enumerator
 *    never yields the same model twice on its own, so the default `0 / 0` (no filter) is
 *    correct for all standard use. Ignored by `solve` / `samples`.
 */
data class BacktrackParams(
    val maxDecisions: Long = Long.MAX_VALUE,
    val randomSeed: Long? = null,
    val assumptions: Assumptions = Assumptions.None,
    val variableHeuristic: VariableHeuristic = RandomVariable,
    val valueHeuristic: ValueHeuristic = IndomainRandom,
    val minHammingDistance: Int = 0,
    val recentWindow: Int = 0,
    /** Cooperative cancellation predicate; see [com.eignex.klause.solver.Cancellation]. */
    val cancellation: com.eignex.klause.solver.Cancellation = com.eignex.klause.solver.NeverCancel,
) : SolverParams {
    override fun withAssumptions(assumptions: Assumptions): BacktrackParams =
        if (assumptions.isEmpty) this else copy(assumptions = merge(this.assumptions, assumptions))

    override fun withCancellation(cancellation: com.eignex.klause.solver.Cancellation): BacktrackParams =
        copy(cancellation = cancellation)

    private companion object {
        fun merge(a: Assumptions, b: Assumptions): Assumptions = a.mergedWith(b)
    }
}
