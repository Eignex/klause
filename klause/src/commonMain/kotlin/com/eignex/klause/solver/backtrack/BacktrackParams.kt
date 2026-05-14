package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.SolverParams

/**
 * How [BacktrackSolver.enumerate] sweeps the search space.
 *
 *  - [Dfs] — single deterministic DFS through the tree; yields distinct SAT leaves in
 *    DFS order, deduped via the rolling Hamming-distance window. Complete: given enough
 *    budget, every distinct feasible assignment is yielded exactly once.
 *  - [RandomRestart] — independent DFS runs per yield, each with a fresh seed. Diverse
 *    coverage of the search space at the cost of completeness — the same assignment can
 *    reappear (the Hamming window filters near-duplicates within the recent window). Use
 *    when the test/verification budget is small and DFS-locked traversal would over-sample
 *    one subtree.
 */
enum class EnumerationMode { Dfs, RandomRestart }

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
 *  - [minHammingDistance] / [recentWindow] — dedup filter for the [BacktrackSolver.enumerate]
 *    path. Ignored by `solve` / `samples`.
 */
data class BacktrackParams(
    val maxDecisions: Long = Long.MAX_VALUE,
    val randomSeed: Long? = null,
    val assumptions: Assumptions = Assumptions.None,
    val variableHeuristic: VariableHeuristic = RandomVariable,
    val valueHeuristic: ValueHeuristic = IndomainRandom,
    val minHammingDistance: Int = 1,
    val recentWindow: Int = 16,
    /**
     * Strategy for [BacktrackSolver.enumerate]. Default [EnumerationMode.Dfs] preserves
     * complete distinct enumeration; switch to [EnumerationMode.RandomRestart] for
     * diverse-but-non-complete sampling.
     */
    val enumerationMode: EnumerationMode = EnumerationMode.Dfs,
) : SolverParams
