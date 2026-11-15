package com.eignex.klause.propagation

import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.StopPolling
import com.eignex.klause.util.pollStop

internal class FinitePropagationInterrupted : RuntimeException()

internal interface InterruptiblePropagator : Propagator {
    fun interrupted(state: PropagationState, factorId: Int)
}

internal class FinitePropagationWork {
    private var current = ExecutionControl.Never
    var cancellation: ExecutionControl
        get() = current
        set(value) {
            if (current === value && value is PropagationControl) return
            current = value
            never = value === ExecutionControl.Never
            polling = value.stopPolling
            begin()
        }
    private var remaining = 63
    private var never = true
    private var polling: StopPolling? = null

    fun <T> withCancellation(value: ExecutionControl, operation: () -> T): T {
        val previous = current
        val previousPolling = polling
        val previousNever = never
        cancellation = value
        return try {
            operation()
        } finally {
            current = previous
            polling = previousPolling
            never = previousNever
            remaining = 63
        }
    }

    fun begin() {
        remaining = 63
    }

    fun check() {
        if (never) return
        val cursor = polling
        if (cursor != null) {
            if (cursor.due(1) && cancellation()) throw FinitePropagationInterrupted()
            return
        }
        if (remaining-- > 0) return
        remaining = 63
        if (cancellation()) throw FinitePropagationInterrupted()
    }

    fun checkNow() {
        remaining = 63
        if (cancellation.pollStop()) throw FinitePropagationInterrupted()
    }
}

internal fun PropagationState.checkFiniteWork() {
    if (work >= workLimit) throw FinitePropagationInterrupted()
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
            // Atom wakes preserve their first establishment and can replay a completed prefix.
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
    // The queue completes this cursor before any factor can read the changed domain's history.
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
    if (finiteMaintenanceActive) finiteWork.check()
    work++
}
