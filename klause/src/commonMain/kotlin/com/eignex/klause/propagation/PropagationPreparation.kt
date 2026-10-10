package com.eignex.klause.propagation

import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation

internal const val PROPAGATION_PREPARATION_BATCH_SIZE: Int = 256

internal class PropagationPreparation(
    problem: Problem,
    private val cancellation: Cancellation,
    private val propagationCancelFloor: Int,
    private val pbLearning: Boolean,
) {
    private val projections = PropagationProblem.preparation(problem)
    private var projection: PropagationProblem? = null
    private var state: PropagationState? = null
    private var rootStarted = false
    private var result: PropagationSession? = null

    val work: Long get() = state?.work ?: 0L
    val propagationNanos: Long get() = state?.propagationNanos ?: 0L
    val cancelled: Boolean get() = state?.runCancelled == true

    fun advance(): PropagationSession? {
        result?.let { return it }
        if (cancelled || cancellation()) return null
        val projected = projection ?: run {
            projection = projections.next()
            return null
        }
        val live = state ?: run {
            state = PropagationState(projected, Assumptions.None, pbLearning = pbLearning).also {
                it.cancelFloor = propagationCancelFloor
            }
            return null
        }
        if (!rootStarted) {
            live.beginRootFixpoint()
            rootStarted = true
            return null
        }
        val conflict = live.advanceRootFixpoint(cancellation)
        if (live.runCancelled || (conflict == null && live.rootFixpointPending)) return null
        return PropagationSession.prepared(live, cancellation, conflict).also { result = it }
    }
}
