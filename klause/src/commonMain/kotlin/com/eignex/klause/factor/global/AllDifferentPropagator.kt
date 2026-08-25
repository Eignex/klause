package com.eignex.klause.factor.global

import com.eignex.klause.factor.global.internals.ReginCache
import com.eignex.klause.factor.global.internals.antecedentsWithPremises
import com.eignex.klause.factor.global.internals.boundsAllDifferentFilter
import com.eignex.klause.factor.global.internals.reginFilter
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongHashSet

/** CP propagation logic for `all_different`. */
internal class AllDifferentPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    private val vars: IntArray,
    private val presents: IntArray,
    private val exceptSet: LongArray,
    private val boundsConsistent: Boolean,
    private val exceptValues: LongHashSet,
    private val definitelyPresentPropFn: (Int, PropagationState) -> Boolean,
) : Propagator {

    override val expensiveBake: Boolean get() = true

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

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? = antecedentsWithPremises(
        state,
        (state.refPayload[factorId] as? ReginCache)?.conflictVars ?: vars,
        presencePremises(state),
    )

    /**
     * The literals falsified by "every filtered position is present" — one per definitely-present
     * position. Filtering reasons only over those positions, so every prune and every Hall conflict is
     * conditional on them; an absent position lifts its column out of the constraint entirely. Positions
     * that are definitely absent contribute nothing to a distinctness violation and are left out.
     */
    private fun presencePremises(state: PropagationState): IntArray {
        if (presents.isEmpty()) return EmptyIntArray
        val out = IntArrayList()
        for (i in presents.indices) {
            if (definitelyPresentPropFn(i, state)) out.add(Lit.negate(presents[i]))
        }
        return out.toIntArray()
    }

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
                if (definitelyPresentPropFn(i, state)) acc.add(i)
            }
            IntArray(acc.size) { acc[it] }
        }
        val n = filtered.size
        if (n < 2) return true
        val filteredVars = IntArray(n) { vars[filtered[it]] }
        val cache = (state.refPayload[factorId] as? ReginCache)
            ?: ReginCache().also { state.refPayload[factorId] = it }
        cache.conflictVars = null
        val hall = reginFilter(state, filteredVars, exceptValues, cache, presencePremises(state))
        if (hall != null) {
            cache.conflictVars = hall
            return false
        }
        return true
    }
}
