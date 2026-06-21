package com.eignex.klause.solver.factor.bool

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.util.IntIntMap

/** Body abstraction for the pseudo-Boolean weighted-sum factors [PseudoBoolean] and
 *  `ReifiedPseudoBoolean`: `Σ weights(i) · lit_i ⟨op⟩ bound` over Boolean literals. */
abstract class PseudoBooleanSumFactor(
    /** Weights, parallel to [literals]. */
    val weights: IntArray,
    /** Boolean literals contributing their weight when true. */
    val literals: IntArray,
    /** Relation between the weighted sum and [bound]. */
    val op: PbOp,
    /** Right-hand-side bound. */
    val bound: Int,
    excludedVar: Int,
) : WeightedSumFactor() {

    init {
        require(weights.size == literals.size) { "weights/literals length mismatch" }
        require(weights.isNotEmpty()) { "pseudo-Boolean sum must have at least one term" }
    }

    internal val signedByVar: IntIntMap = buildSignedWeightByVar(weights, literals, exclude = excludedVar)

    final override val intVars: IntArray = EmptyIntArray

    final override fun holds(sum: Long): Boolean = pbHolds(sum, op, bound)

    final override fun residual(sum: Long, softCap: Int): Int = compressViolation(pbDistance(sum, op, bound), softCap)
}
