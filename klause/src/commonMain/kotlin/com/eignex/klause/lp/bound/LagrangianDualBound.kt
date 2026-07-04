package com.eignex.klause.lp.bound

import com.eignex.klause.propagation.PropagationSession

/**
 * A Lagrangian dual relaxation bound with a persistent multiplier vector warm-started across nodes.
 * Implemented by [LagrangianBound] (#429) and [KnapsackLagrangianBound]; the backtrack LP prune
 * cascade drives both through one arm that threads the multipliers.
 */
internal interface LagrangianDualBound {
    /** Length of the multiplier vector this bound optimizes (its warm-start dimension). */
    val multiplierCount: Int

    /**
     * Compute the Lagrangian bound at the current node. [incumbent] is the best objective to beat
     * (`+∞` if none — the subgradient is skipped and only the base bound / infeasibility is
     * reported). [startMultipliers] warm-starts λ from a parent node. Returns null when the bound
     * is unavailable here (no eligible global, value set too large, or arithmetic overflow).
     */
    fun computeBound(
        session: PropagationSession,
        incumbent: Double,
        startMultipliers: LongArray,
        iterations: Int,
    ): LagrangianResult?
}

/** Outcome of a [LagrangianDualBound.computeBound]: whether the node prunes, the bound as a
 *  numerator/denominator pair, and the (re-optimized) multipliers to carry to the next node. */
internal class LagrangianResult(
    val prune: Boolean,
    val boundNumerator: Long,
    val denominator: Long,
    val multipliers: LongArray,
)
