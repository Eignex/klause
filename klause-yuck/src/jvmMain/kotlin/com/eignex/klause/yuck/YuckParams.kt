package com.eignex.klause.yuck

import com.eignex.klause.solver.SolverParams

/**
 * Per-call params for [YuckSolver].
 *
 *  - [timeoutMillis] — wall-clock cap passed to Yuck as `--runtime-limit` (rounded up to whole
 *    seconds). `null` = no cap; fine for `solve` (Yuck stops at the first solution) but a
 *    `minimize` without a cap only terminates if Yuck proves the optimum, which a local-search
 *    engine rarely does — always budget optimization calls.
 *  - [maxModels] — caps how many solutions [YuckSolver.enumerate] / `samples` yield. Yuck has
 *    no exhaustive enumeration (it is local search); each model is an independent run with a
 *    derived seed, so duplicates are possible.
 *  - [seed] — Yuck's random seed (`--seed`), for reproducible runs.
 *  - [solvers] — Yuck's portfolio size / thread count (`--number-of-solvers`). Kept at 1 so a
 *    parity sweep measures the single-threaded engine, mirroring the klause-LS configs it is
 *    diffed against.
 *
 * Yuck is used as a local-search **reference** for the LS parity sweep, so its params are
 * intentionally minimal — assumptions/cancellation fall back to the [SolverParams] no-op
 * defaults.
 */
data class YuckParams(
    val timeoutMillis: Long? = null,
    val maxModels: Long = Long.MAX_VALUE,
    val seed: Long = 0L,
    val solvers: Int = 1,
) : SolverParams
