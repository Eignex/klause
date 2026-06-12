package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor

/** Common parent of the weighted-sum factors: a running `Σ` in `longPayload(factorId)` with the
 *  shared [holds] / [residual] / [degree] contract over it. */
abstract class WeightedSumFactor : Factor {

    protected abstract fun holds(sum: Long): Boolean

    protected abstract fun residual(sum: Long, softCap: Int): Int

    protected fun degree(sum: Long, softCap: Int): Int = if (holds(sum)) 0 else residual(sum, softCap)
}
