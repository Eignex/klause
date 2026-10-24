package com.eignex.klause.propagation

import com.eignex.klause.util.Cancellation

internal class FinitePropagationInterrupted : RuntimeException()

internal interface InterruptiblePropagator : Propagator {
    fun interrupted(state: PropagationState, factorId: Int)
}

internal class FinitePropagationWork {
    var cancellation: Cancellation = Cancellation.Never
    private var remaining = 63

    fun begin() {
        remaining = 63
    }

    fun check() {
        if (cancellation === Cancellation.Never) return
        if (cancellation.workMeter() == null && remaining-- > 0) return
        checkNow()
    }

    fun checkNow() {
        remaining = 63
        if (cancellation()) throw FinitePropagationInterrupted()
    }
}

internal fun PropagationState.checkFiniteWork() {
    finiteWork.check()
    work++
}
