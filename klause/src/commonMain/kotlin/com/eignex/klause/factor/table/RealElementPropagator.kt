package com.eignex.klause.factor.table

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator

internal class RealElementPropagator(private val factor: RealElement) : Propagator {
    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        state.tightenIntMin(factor.idx, factor.indexOffset.toLong()) &&
            state.tightenIntMax(factor.idx, factor.indexOffset.toLong() + factor.size - 1)
}
