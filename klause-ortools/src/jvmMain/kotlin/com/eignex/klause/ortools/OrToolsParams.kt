package com.eignex.klause.ortools

import com.eignex.klause.solver.SolverParams

/**
 * Per-call params for [OrToolsSolver].
 *
 *  - [timeoutMillis] — wall-clock cap on the CP-SAT search. `null` = no cap.
 *  - [workers] — number of parallel CP-SAT search workers (0 = OR-Tools default).
 *  - [maxModels] — caps how many solutions [OrToolsSolver.enumerate] / `samples` yield.
 *
 * OR-Tools CP-SAT is used as the **anytime / LS reference**: it reports incumbents over time
 * via [OrToolsSolver.improvements], which the bench's anytime metric compares against
 * klause-LS. Assumptions/cancellation fall back to the [SolverParams] no-op defaults.
 */
data class OrToolsParams(val timeoutMillis: Long? = null, val workers: Int = 0, val maxModels: Long = Long.MAX_VALUE) :
    SolverParams
