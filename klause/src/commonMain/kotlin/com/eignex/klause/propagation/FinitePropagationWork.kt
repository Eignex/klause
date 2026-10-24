package com.eignex.klause.propagation

import com.eignex.klause.util.Cancellation

internal class FinitePropagationInterrupted : RuntimeException()

internal interface InterruptiblePropagator : Propagator {
    fun interrupted(state: PropagationState, factorId: Int)
}

internal class FinitePropagationWork {
    var cancellation: Cancellation = Cancellation.Never
        set(value) {
            field = value
            begin()
        }
    private var remaining = 63
    private var metered = false
    private var never = true

    fun begin() {
        remaining = 63
        metered = cancellation.workMeter() != null
        never = cancellation === Cancellation.Never
    }

    fun check() {
        if (never) return
        if (!metered && remaining-- > 0) return
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
