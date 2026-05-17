package com.eignex.klause.logicng

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.SolverParams

/**
 * Per-call params for [LogicNGSolver].
 *
 *  - [randomSeed] — currently advisory; LogicNG's MiniSat does not expose a public seed
 *    parameter on the version we pin. Documented for future use; setting it has no effect today.
 *  - [minHammingDistance] / [recentWindow] — opt-in diversity post-filter on
 *    [LogicNGSolver.enumerate]. Default `0 / 0` means no filter; model-blocking
 *    enumeration yields each model at most once already.
 *  - [maxModels] — caps the number of model-enumeration attempts before the sequence ends.
 *  - [timeoutMillis] — wall-clock cap. Checked between solves; a long-running individual
 *    solve will not be interrupted mid-call.
 *  - [assumptions] — variables to pin for the duration of this call. Bare [LogicNGSolver]
 *    silently drops them (its underlying MiniSat is rebuilt per call, so per-call pins
 *    would have nowhere to ride). [LogicNGSession] consumes them as MiniSat per-call
 *    literal assumptions — useful for incremental solving under varying pinned subsets.
 */
data class LogicNGParams(
    val randomSeed: Long? = null,
    val minHammingDistance: Int = 0,
    val recentWindow: Int = 0,
    val maxModels: Long = Long.MAX_VALUE,
    val timeoutMillis: Long? = null,
    val assumptions: Assumptions = Assumptions.None,
) : SolverParams {
    override fun withAssumptions(assumptions: Assumptions): LogicNGParams =
        if (assumptions.isEmpty) this else copy(assumptions = merge(this.assumptions, assumptions))

    private companion object {
        fun merge(a: Assumptions, b: Assumptions): Assumptions {
            if (a.isEmpty) return b
            if (b.isEmpty) return a
            val bools = HashMap<Int, Boolean>(a.bools).apply { putAll(b.bools) }
            val ints = HashMap<Int, Int>(a.ints).apply { putAll(b.ints) }
            return Assumptions(bools, ints)
        }
    }
}
