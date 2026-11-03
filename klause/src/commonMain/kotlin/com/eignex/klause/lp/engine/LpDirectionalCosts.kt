package com.eignex.klause.lp.engine

import com.eignex.klause.util.Cancellation

internal data class LpDirectionalCost(val down: Double, val up: Double)

// Costs follow the model's minimization orientation and carry no bound or proof authority.
internal interface LpDirectionalCostSolver : LpSolver {
    // The caller funds at most two structural columns before allocation or basis access.
    // An unavailable estimate starts no solve, pivot or certification.
    fun directionalCosts(
        pointModel: LpModel,
        columns: IntArray,
        allowance: Long,
        token: Cancellation,
        charge: (Long) -> Unit,
    ): List<LpDirectionalCost?>?
}
