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

internal class FiniteDomainCompletion(
    private val factor: Int,
    private val level: Int,
    private val historyCount: Int,
    private val history: (Int) -> Unit,
    private val finish: () -> Unit,
) {
    private var next = 0

    fun advance(state: PropagationState) {
        val previousFactor = state.currentFactor
        val previousLevel = state.currentLevel
        val previousMaintenance = state.finiteMaintenanceActive
        state.currentFactor = factor
        state.currentLevel = level
        state.finiteMaintenanceActive = true
        try {
            while (next < historyCount) {
                state.checkFiniteWork()
                history(next)
                next++
            }
            finish()
        } finally {
            state.currentFactor = previousFactor
            state.currentLevel = previousLevel
            state.finiteMaintenanceActive = previousMaintenance
        }
    }
}

internal fun PropagationState.completeDomainMutation(
    historyCount: Int,
    history: (Int) -> Unit,
    finish: () -> Unit,
) {
    check(pendingFiniteMutation == null)
    if (!finiteMutationInterruptible) {
        repeat(historyCount, history)
        finish()
        return
    }
    pendingFiniteMutation = FiniteDomainCompletion(currentFactor, currentLevel, historyCount, history, finish)
    completePendingFiniteMutation()
}

internal fun PropagationState.completePendingFiniteMutation() {
    val pending = pendingFiniteMutation ?: return
    pending.advance(this)
    pendingFiniteMutation = null
}

internal fun PropagationState.checkFiniteMutationWork() {
    if (finiteMutationInterruptible) checkFiniteWork()
}

internal fun PropagationState.checkFiniteAtomWork() {
    if (finiteMaintenanceActive) checkFiniteWork()
}
