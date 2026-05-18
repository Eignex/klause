package com.eignex.klause.z3

import com.eignex.klause.solver.SolverParams

/**
 * Per-call params for [Z3Solver]. Mirrors [com.eignex.klause.logicng.LogicNGParams] in
 * shape so the cross-backend harness can hand each backend its own typed params and stay
 * symmetric.
 *
 *  - [randomSeed] — set on Z3's `random_seed` global. Affects branching when there is
 *    actual nondeterminism; on small problems Z3 may still return the same model.
 *  - [minHammingDistance] / [recentWindow] — opt-in diversity post-filter on
 *    [Z3Solver.enumerate]. Default `0 / 0` means no filter; model-blocking enumeration
 *    yields each model at most once already.
 *  - [maxModels] — caps the number of model attempts before the sequence ends.
 *  - [timeoutMillis] — wall-clock cap. Checked between solves.
 */
data class Z3Params(
    val randomSeed: Long? = null,
    val minHammingDistance: Int = 0,
    val recentWindow: Int = 0,
    val maxModels: Long = Long.MAX_VALUE,
    val timeoutMillis: Long? = null,
    /**
     * Wall-clock-independent operation budget — wired to Z3's `rlimit` solver parameter.
     * Z3 ticks `rlimit` on internal events (propagations, decisions, conflicts, etc.); the
     * exact mapping to "instructions" is solver-version-specific but reproducible across
     * runs on the same Z3 build. Typical values: 1e5 (fast probe), 1e7 (medium), 1e9
     * (long). `null` = no cap. The match for `maxInstructions` on the internal backends'
     * params; same field name across backends keeps cross-backend callers symmetric.
     */
    val maxInstructions: Long? = null,
) : SolverParams
