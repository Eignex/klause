package com.eignex.klause.logicng

import com.eignex.klause.solver.SolverParams

/**
 * Per-call params for [LogicNGSampler].
 *
 *  - [randomSeed] — currently advisory; LogicNG's MiniSat does not expose a public seed
 *    parameter on the version we pin. Documented for future use; setting it has no effect today.
 *  - [minHammingDistance] / [recentWindow] — apply to [LogicNGSampler.enumerate] as a
 *    post-filter, identical to the local-search backend's semantics.
 *  - [maxModels] — caps the number of model-enumeration attempts before the sequence ends.
 *  - [timeoutMillis] — wall-clock cap. Checked between solves; a long-running individual
 *    solve will not be interrupted mid-call.
 */
data class LogicNGParams(
    val randomSeed: Long? = null,
    val minHammingDistance: Int = 1,
    val recentWindow: Int = 16,
    val maxModels: Long = Long.MAX_VALUE,
    val timeoutMillis: Long? = null,
) : SolverParams
