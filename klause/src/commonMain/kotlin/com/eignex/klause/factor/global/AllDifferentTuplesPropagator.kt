package com.eignex.klause.factor.global

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator

internal class AllDifferentTuplesPropagator(private val factor: AllDifferentTuples) : Propagator {
    override val initialIntEventWatches: IntArray =
        factor.intVars.map { IntEvent.pack(it, IntEvent.FIXED) }.toIntArray()

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        state.propagatorFailures.remove(this)
        if (factor.repeatedTuple) {
            state.propagatorFailures[this] = IntArray(0)
            return false
        }
        val fixed = HashMap<TupleValues, Int>()
        for ((index, tuple) in factor.tuples.withIndex()) {
            if (tuple.any { state.intDomains[it].min != state.intDomains[it].max }) continue
            val key = TupleValues(LongArray(tuple.size) { state.intDomains[tuple[it]].min })
            val previous = fixed.put(key, index) ?: continue
            state.propagatorFailures[this] = collectHoleAndBoundAntecedents(
                state, factor.tuples[previous] + tuple,
            ) ?: IntArray(0)
            return false
        }
        return true
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.propagatorFailures[this]
}
