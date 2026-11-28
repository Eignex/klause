package com.eignex.klause.localsearch

import com.eignex.util.arrays.emptyIntArray

// Search state a [LocalSearchSession] carries across calls; a null field falls back to the strategy's default.
internal class WarmState {
    internal var factorWeights: DoubleArray? = null

    // Indexed like TabuBook.touchCount; read by the ALNS activity-biased destroy operator.
    internal var activityTouches: IntArray? = null

    // Kept across calls so best-ever aspiration sees the session's full history.
    internal var bestCostSeen: Long = Long.MAX_VALUE

    fun activityTouches(): IntArray = activityTouches ?: emptyIntArray()

    fun bestCostSeen(): Long = bestCostSeen

    fun reset() {
        factorWeights = null
        activityTouches = null
        bestCostSeen = Long.MAX_VALUE
    }

    // Arrays whose size does not match the current problem are dropped.
    internal fun applyTo(state: LocalSearchState) {
        applySteps(state).forEach { }
    }

    internal fun applySteps(state: LocalSearchState): Sequence<Unit> = sequence {
        factorWeights?.let { w ->
            yieldAll(state.weights.preparation())
            val target = state.weights.factorWeights
            if (w.size == target.size) {
                for (from in w.indices step INITIALIZATION_BATCH) {
                    w.copyInto(target, from, from, minOf(from + INITIALIZATION_BATCH, w.size))
                    yield(Unit)
                }
            }
        }
        activityTouches?.let { t ->
            val target = state.tabu.touchCount
            if (t.size == target.size) {
                for (from in t.indices step INITIALIZATION_BATCH) {
                    t.copyInto(target, from, from, minOf(from + INITIALIZATION_BATCH, t.size))
                    yield(Unit)
                }
            }
        }
        if (bestCostSeen < state.bestCostSeen) state.bestCostSeen = bestCostSeen
    }

    // Weight-blind strategies never allocate weights, and capturing the defaults would force that allocation.
    internal fun captureFrom(state: LocalSearchState) {
        if (state.weights.allocated) {
            factorWeights = state.weights.factorWeights.copyOf()
        }
        activityTouches = state.tabu.touchCount.copyOf()
        if (state.bestCostSeen < bestCostSeen) bestCostSeen = state.bestCostSeen
    }
}
