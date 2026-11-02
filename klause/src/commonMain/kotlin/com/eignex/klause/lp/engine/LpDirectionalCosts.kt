package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation

internal data class LpDirectionalCost(val down: Double, val up: Double)

/** Optional advisory sensitivity of a current optimal basis, with no bound or proof authority. */
internal interface LpDirectionalCostSolver : LpSolver {
    /**
     * Estimate objective cost per source-coordinate unit for at most two structural [columns].
     * [charge] reserves all analysis work before allocation or basis access; a decline never solves,
     * pivots or certifies. Missing directions remain unavailable rather than claiming infeasibility.
     */
    fun directionalCosts(
        pointModel: LpModel,
        columns: IntArray,
        allowance: Long,
        token: Cancellation,
        charge: (Long) -> Unit,
    ): List<LpDirectionalCost?>?
}
