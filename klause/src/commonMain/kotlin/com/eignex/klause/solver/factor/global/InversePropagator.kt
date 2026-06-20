package com.eignex.klause.solver.factor.global

import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.solver.factor.global.internals.InverseCache
import com.eignex.klause.solver.factor.global.internals.reginFilter
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.IntIntMap

/** CP propagation logic for `inverse`. */
internal interface InversePropagator : Propagator {
    val f: IntArray
    val g: IntArray
    val fOffset: Int
    val gOffset: Int
    val fIndexOf: IntIntMap
    val gIndexOf: IntIntMap

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, (state.refPayload[factorId] as? InverseCache)?.conflictVars ?: intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val cache = (state.refPayload[factorId] as? InverseCache) ?: run {
            val fresh = InverseCache()
            state.refPayload[factorId] = fresh
            fresh
        }
        cache.conflictVars = null
        val full = !cache.initialized
        val fDirty = BooleanArray(f.size)
        val gDirty = BooleanArray(g.size)
        if (!full) {
            for (v in state.drainIntEventDirtyVars(factorId)) {
                val fi = fIndexOf[v]
                if (fi >= 0) fDirty[fi] = true
                val gi = gIndexOf[v]
                if (gi >= 0) gDirty[gi] = true
            }
        } else {
            state.drainIntEventDirtyVars(factorId)
        }
        val gLo = gOffset
        val gHi = gOffset + g.size - 1
        for (i in f.indices) {
            if (!state.tightenIntMin(f[i], gLo)) {
                cache.conflictVars = intArrayOf(f[i])
                return false
            }
            if (!state.tightenIntMax(f[i], gHi)) {
                cache.conflictVars = intArrayOf(f[i])
                return false
            }
        }
        val fLo = fOffset
        val fHi = fOffset + f.size - 1
        for (i in g.indices) {
            if (!state.tightenIntMin(g[i], fLo)) {
                cache.conflictVars = intArrayOf(g[i])
                return false
            }
            if (!state.tightenIntMax(g[i], fHi)) {
                cache.conflictVars = intArrayOf(g[i])
                return false
            }
        }
        for (i in f.indices) {
            val d = state.intDomains[f[i]]
            if (d.min != d.max) continue
            val gIdx = d.min - gOffset
            if (gIdx !in g.indices) {
                cache.conflictVars = intArrayOf(f[i])
                return false
            }
            val ant = state.composeIntVarAtomAntecedents(intArrayOf(f[i]))
            if (!state.tightenIntMin(g[gIdx], i + fOffset, ant)) {
                cache.conflictVars = intArrayOf(f[i], g[gIdx])
                return false
            }
            if (!state.tightenIntMax(g[gIdx], i + fOffset, ant)) {
                cache.conflictVars = intArrayOf(f[i], g[gIdx])
                return false
            }
        }
        for (i in g.indices) {
            val d = state.intDomains[g[i]]
            if (d.min != d.max) continue
            val fIdx = d.min - fOffset
            if (fIdx !in f.indices) {
                cache.conflictVars = intArrayOf(g[i])
                return false
            }
            val ant = state.composeIntVarAtomAntecedents(intArrayOf(g[i]))
            if (!state.tightenIntMin(f[fIdx], i + gOffset, ant)) {
                cache.conflictVars = intArrayOf(g[i], f[fIdx])
                return false
            }
            if (!state.tightenIntMax(f[fIdx], i + gOffset, ant)) {
                cache.conflictVars = intArrayOf(g[i], f[fIdx])
                return false
            }
        }
        fun fChanged(i: Int) = full || fDirty[i]
        fun gChanged(j: Int) = full || gDirty[j]
        fun pair(i: Int, gIdx: Int): Boolean {
            val jVal = gIdx + gOffset
            val iVal = i + fOffset
            val fHas = jVal in state.intDomains[f[i]]
            val gHas = iVal in state.intDomains[g[gIdx]]
            if (fHas && !gHas) {
                val ant = state.composeIntVarAtomAntecedents(intArrayOf(g[gIdx]))
                if (!state.excludeIntValue(f[i], jVal, ant)) {
                    cache.conflictVars = intArrayOf(f[i], g[gIdx])
                    return false
                }
            } else if (!fHas && gHas) {
                val ant = state.composeIntVarAtomAntecedents(intArrayOf(f[i]))
                if (!state.excludeIntValue(g[gIdx], iVal, ant)) {
                    cache.conflictVars = intArrayOf(f[i], g[gIdx])
                    return false
                }
            }
            return true
        }
        for (i in f.indices) {
            if (!fChanged(i)) continue
            for (gIdx in g.indices) if (!pair(i, gIdx)) return false
        }
        for (gIdx in g.indices) {
            if (!gChanged(gIdx)) continue
            for (i in f.indices) {
                if (fChanged(i)) continue
                if (!pair(i, gIdx)) return false
            }
        }
        val fHall = reginFilter(state, f, NO_EXCEPT, cache.fRegin)
        if (fHall != null) {
            cache.conflictVars = fHall
            return false
        }
        val gHall = reginFilter(state, g, NO_EXCEPT, cache.gRegin)
        if (gHall != null) {
            cache.conflictVars = gHall
            return false
        }
        cache.initialized = true
        return true
    }

    companion object {
        val NO_EXCEPT = IntHashSet()
    }
}
