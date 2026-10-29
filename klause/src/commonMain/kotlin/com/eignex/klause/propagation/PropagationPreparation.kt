package com.eignex.klause.propagation

import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation

internal const val PROPAGATION_PREPARATION_BATCH_SIZE: Int = 256

internal class PropagationPreparation(
    problem: Problem,
    private val cancellation: Cancellation,
    private val propagationCancelFloor: Int,
    private val pbLearning: Boolean,
    private val cumulativeUnarySubsets: Boolean = false,
    private val attribution: Boolean = false,
    private val historicalTables: Boolean = false,
) {
    private val projections = PropagationProblem.preparation(problem)
    private var projection: PropagationProblem? = null
    private var state: PropagationState? = null
    private var states: Iterator<PropagationState?>? = null
    private var rootQueue: Iterator<Unit>? = null
    internal var rootStarted = false
        private set
    private var sessions: Iterator<PropagationSession?>? = null
    private var result: PropagationSession? = null

    var isUnsat: Boolean = false
        private set

    val work: Long get() = state?.work ?: 0L
    val propagationNanos: Long get() = state?.propagationNanos ?: 0L
    fun learning(): Map<String, Long> = state?.factorAttribution?.snapshot() ?: emptyMap()

    fun advance(): PropagationSession? {
        result?.let { return it }
        if (cancellation()) return null
        val projected = projection ?: run {
            projection = projections.next()
            return null
        }
        val live = state ?: run {
            val builder = states ?: PropagationState.preparation(projected, pbLearning).also { states = it }
            state = builder.next()?.also {
                it.cancelFloor = propagationCancelFloor
                it.cumulativeUnarySubsets = cumulativeUnarySubsets
                if (attribution) it.factorAttribution = FactorAttribution()
                it.historicalTableReasons = historicalTables
                states = null
            }
            return null
        }
        if (!rootStarted) {
            val queue = rootQueue ?: live.beginRootFixpoint().also { rootQueue = it }
            if (queue.hasNext()) queue.next() else {
                rootStarted = true
                rootQueue = null
            }
            return null
        }
        val builder = sessions ?: run {
            val conflict = live.advanceRootFixpoint(cancellation)
            if (conflict == null && live.rootFixpointPending) return null
            isUnsat = conflict != null
            PropagationSession.preparation(live, cancellation, conflict).also { sessions = it }
        }
        return builder.next()?.also {
            result = it
            sessions = null
        }
    }
}
