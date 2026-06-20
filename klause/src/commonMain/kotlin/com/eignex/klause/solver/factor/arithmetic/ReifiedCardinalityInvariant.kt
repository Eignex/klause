package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Invariant

/**
 * LS contract for [ReifiedCardinality]: reified cardinality violation tracking and repair.
 *
 * Default implementations of `deltaIfBoolFlipped`, `applyBoolFlip`, and `updateBoolBreakMakeForFlip`
 * are left abstract here because they require access to protected parent helpers (`holds`, `residual`)
 * and the internal `signedByVar` map. The concrete class [ReifiedCardinality] implements them directly.
 */
interface ReifiedCardinalityInvariant : Invariant {

    /** The reifying Boolean variable id. */
    val auxBoolVar: Int

    /** The Boolean literals. */
    val literals: IntArray

    /** Inclusive lower bound. */
    val min: Int

    /** Inclusive upper bound (also used as `true` for max-mode in `ArrayMinMax`). */
    val max: Int

    override val maintainsBreakMakeIncrementally: Boolean get() = true
}
