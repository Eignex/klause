package com.eignex.klause.solver

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
    val randomSeed: Long? = null,
    val minHammingDistance: Int = 1,
    val recentWindow: Int = 16,
    /** Variables to pin for the duration of this call. The solver initialises them to
     *  the requested values on every restart and ignores any move that would change
     *  them. Defaults to none. */
    val assumptions: Assumptions = Assumptions.None,
) : SolverParams
