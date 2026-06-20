package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/** CP propagation logic for `nvalue`. */
internal interface NValuePropagator : Propagator {
    val n: Int
    val xs: IntArray
    val mode: NValue.Mode
    val presents: IntArray

    fun definitelyAbsentNv(idx: Int, state: PropagationState): Boolean
    fun definitelyPresentNv(idx: Int, state: PropagationState): Boolean

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (presents.isEmpty()) {
            val gate = (state.refPayload[factorId] as? NValueCpGate) ?: run {
                val fresh = NValueCpGate()
                state.refPayload[factorId] = fresh
                fresh
            }
            val dirty = state.drainIntEventDirtyVars(factorId)
            if (gate.started && dirty.isEmpty()) return true
            gate.started = true
        }
        val unionValues = IntHashSet()
        for (i in xs.indices) {
            if (definitelyAbsentNv(i, state)) continue
            state.intDomains[xs[i]].forEach { unionValues.add(it) }
        }
        val maxDistinct = unionValues.size
        val present = IntArrayList(xs.size)
        for (i in xs.indices) if (definitelyPresentNv(i, state)) present.add(xs[i])
        present.sortByIntKey { state.intDomains[it].size }
        val covered = IntHashSet()
        var minDistinct = 0
        for (idx in 0 until present.size) {
            val d = state.intDomains[present[idx]]
            var disjoint = true
            d.forEach { if (covered.contains(it)) disjoint = false }
            if (disjoint) {
                minDistinct++
                d.forEach { covered.add(it) }
            }
        }
        val ant = collectHoleAndBoundAntecedents(state, xs)
        when (mode) {
            NValue.Mode.Eq -> {
                if (!state.tightenIntMin(n, minDistinct, ant)) return false
                if (!state.tightenIntMax(n, maxDistinct, ant)) return false
            }

            NValue.Mode.AtLeast -> {
                if (!state.tightenIntMax(n, maxDistinct, ant)) return false
            }

            NValue.Mode.AtMost -> {
                if (!state.tightenIntMin(n, minDistinct, ant)) return false
            }
        }
        return true
    }
}

internal class NValueCpGate {
    var started: Boolean = false
}
