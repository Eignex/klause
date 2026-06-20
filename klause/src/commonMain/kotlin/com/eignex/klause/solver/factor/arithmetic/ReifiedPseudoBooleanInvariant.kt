package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Invariant

/**
 * LS contract for [ReifiedPseudoBoolean]: reified pseudo-Boolean violation tracking and repair.
 *
 * Default implementations of `deltaIfBoolFlipped`, `applyBoolFlip`, and `updateBoolBreakMakeForFlip`
 * are left abstract here because they require access to protected parent helpers (`holds`, `residual`)
 * and the internal `signedByVar` map. The concrete class [ReifiedPseudoBoolean] implements them directly.
 */
interface ReifiedPseudoBooleanInvariant : Invariant {

    /** The reifying Boolean variable id. */
    val auxBoolVar: Int

    /** Literal weights parallel to [literals]. */
    val weights: IntArray

    /** The Boolean literals. */
    val literals: IntArray

    /** Comparison operator. */
    val op: PbOp

    /** Right-hand-side bound. */
    val bound: Int

    override val maintainsBreakMakeIncrementally: Boolean get() = true
}
