package com.eignex.klause.factor.bool

import com.eignex.klause.factor.CoeffLookup
import com.eignex.klause.factor.bool.internals.buildParityByVar
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.InterruptiblePropagator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.checkFiniteWork

internal class XorPropagator(
    val boolVars: IntArray,
    val intVars: IntArray,
    literals: IntArray,
    private val targetParity: Int,
) : InterruptiblePropagator {
    private val parityByVar: CoeffLookup? = if (boolVars.size == literals.size) null
    else buildParityByVar(boolVars, literals)
    private val negativeParity = literals.count { !Lit.isPositive(it) } and 1

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val unassigned = state.refPayload[factorId] as? UnassignedPair
        state.refPayload[factorId] = null
        state.finiteWork.begin()
        state.finiteWork.checkNow()
        if (unassigned != null) {
            state.checkFiniteWork()
            val firstFree = state.boolValues[unassigned.first] == null
            state.checkFiniteWork()
            if (firstFree && state.boolValues[unassigned.second] == null) {
                state.refPayload[factorId] = unassigned
                return true
            }
        }
        var pinnedParity = negativeParity
        var effective = -1
        for (variable in boolVars) {
            state.checkFiniteWork()
            if (parityByVar?.coeffOf(variable) == 0) continue
            val value = state.boolValues[variable]
            if (value == null) {
                if (effective >= 0) {
                    state.checkFiniteWork()
                    state.refPayload[factorId] = UnassignedPair(effective, variable)
                    return true
                }
                effective = variable
            } else if (value) pinnedParity = pinnedParity xor 1
        }
        if (effective >= 0) {
            val reason = parityAntecedents(state, effective)
            return state.pinBool(effective, pinnedParity != targetParity, reason)
        }
        if (pinnedParity == targetParity) return true
        state.refPayload[factorId] = parityAntecedents(state, -1)
        return false
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        state.refPayload[factorId] as? IntArray

    override fun interrupted(state: PropagationState, factorId: Int) {
        state.refPayload[factorId] = null
    }

    private class UnassignedPair(val first: Int, val second: Int)

    private fun parityAntecedents(state: PropagationState, excludeVar: Int): IntArray? {
        var size = 0
        for (variable in boolVars) {
            state.checkFiniteWork()
            if (variable != excludeVar && state.boolValues[variable] != null) size++
        }
        if (size == 0) return null
        val result = IntArray(size)
        var next = 0
        for (variable in boolVars) {
            state.checkFiniteWork()
            if (variable == excludeVar) continue
            val value = state.boolValues[variable] ?: continue
            result[next++] = Lit.make(variable, !value)
        }
        return result
    }
}
