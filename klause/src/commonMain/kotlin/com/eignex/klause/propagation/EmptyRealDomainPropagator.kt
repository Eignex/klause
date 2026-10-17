package com.eignex.klause.propagation

internal object EmptyRealDomainPropagator : Propagator {
    override fun propagate(state: PropagationState, factorId: Int): Boolean = false
}
