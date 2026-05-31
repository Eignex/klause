package com.eignex.klause.choco

import com.eignex.klause.solver.SolverParams

/**
 * Per-call params for [ChocoSolver].
 *
 *  - [timeoutMillis] — wall-clock cap applied to the underlying Choco search. `null` = no cap.
 *  - [maxModels] — caps how many solutions [ChocoSolver.enumerate] / `samples` yield.
 *
 * Choco is used as a complete-search **reference** for differential parity, so its params
 * are intentionally minimal — assumptions/cancellation fall back to the [SolverParams]
 * no-op defaults.
 */
data class ChocoParams(
    val timeoutMillis: Long? = null,
    val maxModels: Long = Long.MAX_VALUE,
) : SolverParams
