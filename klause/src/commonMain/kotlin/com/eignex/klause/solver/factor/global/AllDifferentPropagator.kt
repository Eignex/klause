package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.factor.global.internals.ReginCache
import com.eignex.klause.solver.factor.global.internals.boundsAllDifferentFilter
import com.eignex.klause.solver.factor.global.internals.reginFilter
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

/** CP propagation logic for `all_different`. */
internal interface AllDifferentPropagator : Propagator {
    val vars: IntArray
    val domainSize: Int
    val domainMin: Int
    val presents: IntArray
    val exceptSet: IntArray
    val boundsConsistent: Boolean
    val exceptValues: IntHashSet

    override val initialIntEventWatches: IntArray?
        get() = if (boundsConsistent && presents.isEmpty() && exceptSet.isEmpty()) {
            val distinctVars = vars.distinct()
            val out = IntArray(distinctVars.size * 2)
            var w = 0
            for (v in distinctVars) {
                out[w++] = IntEvent.pack(v, IntEvent.LB_RAISED)
                out[w++] = IntEvent.pack(v, IntEvent.UB_LOWERED)
            }
            out
        } else {
            null
        }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, (state.refPayload[factorId] as? ReginCache)?.conflictVars ?: vars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (boundsConsistent && presents.isEmpty() && exceptSet.isEmpty()) {
            val hall = boundsAllDifferentFilter(state, vars)
            if (hall != null) {
                val cache = (state.refPayload[factorId] as? ReginCache)
                    ?: ReginCache().also { state.refPayload[factorId] = it }
                cache.conflictVars = hall
                return false
            }
            return true
        }
        val filtered: IntArray = if (presents.isEmpty()) {
            IntArray(vars.size) { it }
        } else {
            val acc = IntArrayList()
            for (i in vars.indices) {
                if (definitelyPresentProp(i, state)) acc.add(i)
            }
            IntArray(acc.size) { acc[it] }
        }
        val n = filtered.size
        if (n < 2) return true
        val filteredVars = IntArray(n) { vars[filtered[it]] }
        val cache = (state.refPayload[factorId] as? ReginCache)
            ?: ReginCache().also { state.refPayload[factorId] = it }
        cache.conflictVars = null
        val hall = reginFilter(state, filteredVars, exceptValues, cache)
        if (hall != null) {
            cache.conflictVars = hall
            return false
        }
        return true
    }

    fun definitelyPresentProp(idx: Int, state: PropagationState): Boolean
}
