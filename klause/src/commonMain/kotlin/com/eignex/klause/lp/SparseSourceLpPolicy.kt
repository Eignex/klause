package com.eignex.klause.lp

import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.ReconstructionLimits

internal data class SparseSourceLpPolicy(
    val preparation: SparseSourceLpLimits = SparseSourceLpLimits(2_000_000L, 64L * 1024L * 1024L),
    val floating: LpFloatAllowance = LpFloatAllowance(150_000_000L, 1024),
    val quantum: Long = 20_000_000L,
    val certificate: ReconstructionLimits = ReconstructionLimits(
        maxCoordinates = Int.MAX_VALUE,
        maxEntries = Int.MAX_VALUE,
        maxWork = 2_000_000L,
        maxAllocation = 64L * 1024L * 1024L,
        maxAttempts = 1,
    ),
    val rowPolicy: SparseSourceLpRowPolicy = SparseSourceLpRowPolicy.IDENTITY,
) {
    init {
        require(floating.work > 0L && floating.iterations > 0 && quantum > 0L)
    }
}
