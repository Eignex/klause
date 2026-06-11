package com.eignex.klause.choco

import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.backtrack.BacktrackParams

/**
 * Per-call params for [ChocoSolver].
 *
 *  - [timeoutMillis] — wall-clock cap applied to the underlying Choco search. `null` = no cap.
 *  - [maxModels] — caps how many solutions [ChocoSolver.enumerate] / `samples` yield.
 *  - [workers] — when > 1, [ChocoSolver.solve] / `minimize` race that many model copies via
 *    Choco's `ParallelPortfolio` (with its default per-model search diversification),
 *    keeping the reference comparable to a multi-worker klause portfolio on the same core
 *    budget. `1` (default) preserves the single-threaded reference behaviour.
 *
 * Choco is used as a complete-search **reference** for differential parity, so its params
 * are intentionally minimal — assumptions/cancellation fall back to the [SolverParams]
 * no-op defaults.
 */
data class ChocoParams(
    val timeoutMillis: Long? = null,
    val maxModels: Long = Long.MAX_VALUE,
    val workers: Int = 1,
    /** Annotation-derived klause search params to mirror onto the Choco model (see
     *  `applyFixedSearch`) for fixed-track comparisons; null leaves Choco's own search. */
    val fixedSearch: BacktrackParams? = null,
) : SolverParams
