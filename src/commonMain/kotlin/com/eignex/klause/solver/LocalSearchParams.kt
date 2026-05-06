package com.eignex.klause.solver

/**
 * Per-call params for the local-search [Solver]. Engine setup ([Solver.strategy],
 * [Solver.maxFlipsBeforeRestart]) lives on the constructor; this data class carries the
 * knobs that vary per [Solver.sample] / [Solver.enumerate] / [Solver.solve] call.
 */
data class LocalSearchParams(
    val maxFlips: Long = Long.MAX_VALUE,
    val randomSeed: Long? = null,
    val minHammingDistance: Int = 1,
    val recentWindow: Int = 16,
) : SolverParams
