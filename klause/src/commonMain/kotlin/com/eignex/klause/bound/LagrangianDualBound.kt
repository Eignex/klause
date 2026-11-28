package com.eignex.klause.bound

import com.eignex.klause.propagation.PropagationSession

// A Lagrangian dual bound whose multipliers are warm-started across nodes (Fisher, "The Lagrangian Relaxation
// Method for Solving Integer Programming Problems", Management Science 1981, https://doi.org/10.1287/mnsc.27.1.1).
internal interface LagrangianDualBound {
    val multiplierCount: Int

    // An infinite incumbent skips the subgradient. Null when no eligible global applies, the value set is too
    // large, or arithmetic overflows.
    fun computeBound(
        session: PropagationSession,
        incumbent: Double,
        startMultipliers: LongArray,
        iterations: Int,
    ): LagrangianResult?
}

internal class LagrangianResult(
    val prune: Boolean,
    val boundNumerator: Long,
    val denominator: Long,
    val multipliers: LongArray,
)
