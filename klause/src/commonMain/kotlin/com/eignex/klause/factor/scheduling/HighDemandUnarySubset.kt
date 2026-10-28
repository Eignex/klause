package com.eignex.klause.factor.scheduling

import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.checkFiniteWork
import com.eignex.klause.util.EmptyIntArray

internal class HighDemandUnarySubset(
    private val starts: IntArray,
    private val durations: LongArray,
    val sourceTasks: IntArray,
) {
    private val unary = DisjunctivePropagator(
        starts.distinct().toIntArray(), starts, durations, EmptyIntArray, EmptyIntArray, starts.size,
        reasonPrefix = REASON_TAG,
    )

    fun clearFailure(state: PropagationState) {
        state.propagatorFailures.remove(unary)
    }

    fun propagate(state: PropagationState, factorId: Int): Boolean {
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
        for (i in starts.indices) {
            state.checkFiniteWork()
            val domain = state.intDomains[starts[i]]
            if (domain.min < -TIME_LIMIT || domain.max > TIME_LIMIT) return true
            first = minOf(first, domain.min)
            last = maxOf(last, domain.max + durations[i])
        }
        // The window caps endpoint shaving; the time magnitudes leave headroom for reversed envelopes.
        if (last - first > WINDOW_LIMIT) return true
        return unary.propagate(state, factorId)
    }

    fun conflictReason(state: PropagationState, factorId: Int): IntArray? = unary.conflictReason(state, factorId)

    fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int, atLevel: Int): IntArray? =
        unary.explain(state, factorId, payload.copyOfRange(1, payload.size), atTrail, atLevel)

    companion object {
        const val REASON_TAG = 5
        const val WINDOW_LIMIT = 4096L
        const val TIME_LIMIT = 1L shl 40
    }
}

internal fun Cumulative.highDemandUnarySubset(): HighDemandUnarySubset? {
    if (unary || n !in 4..128 || presents.isNotEmpty() || durationVars.isNotEmpty() ||
        resourceVars.isNotEmpty() || capacityVar >= 0 || capacity > (1L shl 20)
    ) return null
    // Two selected tasks exceed capacity whenever they overlap; zero-duration tasks occupy no time.
    val tasks = starts.indices.filter { durations[it] > 0 && resources[it] > capacity / 2 }
    if (tasks.size !in 3..32 || tasks.size == n ||
        tasks.any { resources[it] > capacity || durations[it] > HighDemandUnarySubset.WINDOW_LIMIT }
    ) return null
    return HighDemandUnarySubset(
        tasks.map { starts[it] }.toIntArray(), tasks.map { durations[it] }.toLongArray(), tasks.toIntArray(),
    )
}
