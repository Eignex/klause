package com.eignex.klause.solver.factor

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * Reified set-membership: `aux ↔ element ∈ setVar`.
 *
 *  - If `aux` is pinned `true`, the propagator requires [element] in [setVar].
 *  - If `aux` is pinned `false`, the propagator excludes [element] from [setVar].
 *  - If [element] is forced into `required`, the propagator pins `aux = true`.
 *  - If [element] is forced out of `possible`, the propagator pins `aux = false`.
 *
 * Use the constructor with [forced] = `true` / `false` for the non-reified `element ∈ setVar`
 * (resp. `element ∉ setVar`) forms; this avoids allocating an aux bool when MZN emits the
 * unreified predicate. When `forced` is set, [auxBoolVar] is ignored at the propagation
 * level and the constraint is treated as a hard requirement.
 */
class SetIn(
    val setVar: Int,
    val element: Int,
    val auxBoolVar: Int = -1,
    val forced: Boolean? = null,
) : LocalSearchFactor {

    init {
        require(forced != null || auxBoolVar >= 0) {
            "SetIn: either supply auxBoolVar (reified) or a forced flag (non-reified)"
        }
    }

    override val boolVars: IntArray =
        if (forced == null) intArrayOf(auxBoolVar) else EmptyIntArray
    override val intVars: IntArray = EmptyIntArray
    override val setVars: IntArray = intArrayOf(setVar)

    override fun initialize(state: LocalSearchState, factorId: Int) {}

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean {
        val inSet = state.assignment.setMember(setVar, element)
        val target = forced ?: state.assignment.boolValue(auxBoolVar)
        return inSet != target
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (forced != null || boolVar != auxBoolVar) return 0
        val wasViolated = isViolated(state, factorId)
        // After flipping aux, the target inverts.
        val inSet = state.assignment.setMember(setVar, element)
        val newTarget = !state.assignment.boolValue(auxBoolVar)
        val willViolate = inSet != newTarget
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun deltaIfSetToggled(state: LocalSearchState, factorId: Int, setVar: Int, element: Int): Int {
        if (setVar != this.setVar || element != this.element) return 0
        val wasViolated = isViolated(state, factorId)
        val newInSet = !state.assignment.setMember(setVar, element)
        val target = forced ?: state.assignment.boolValue(auxBoolVar)
        val willViolate = newInSet != target
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        if (forced != null || boolVar != auxBoolVar) return 0
        // Caller already flipped the bool; report the realized delta.
        val inSet = state.assignment.setMember(setVar, element)
        val target = state.assignment.boolValue(auxBoolVar)
        val nowViolated = inSet != target
        // Before the flip, target was its inverse.
        val wasViolated = inSet != !target
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applySetToggle(state: LocalSearchState, factorId: Int, setVar: Int, element: Int): Int {
        if (setVar != this.setVar || element != this.element) return 0
        val nowInSet = state.assignment.setMember(setVar, element)
        val target = forced ?: state.assignment.boolValue(auxBoolVar)
        val nowViolated = nowInSet != target
        val wasViolated = !nowInSet != target
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        // Two ways to satisfy: flip the aux bool, or toggle the element's membership. Only
        // toggling is legal when the element isn't in the LS-mutable region (i.e. excluded
        // or required by the static domain) — fall back to the bool flip in that case.
        val sd = state.problem.setDomains[setVar]
        val toggleAllowed = sd.possible.get(element) && !sd.required.get(element)
        if (toggleAllowed) sink.addSetToggle(setVar, element)
        if (forced == null) sink.addBoolFlip(auxBoolVar)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val req = state.setRequired[setVar].get(element)
        val poss = state.setPossible[setVar].get(element)
        val target: Boolean? = when {
            forced != null -> forced
            else -> state.boolValues[auxBoolVar]
        }
        when (target) {
            true -> {
                if (!poss) return false
                if (!req) {
                    if (!state.requireElement(setVar, element)) return false
                }
            }
            false -> {
                if (req) return false
                if (poss) {
                    if (!state.excludeElement(setVar, element)) return false
                }
            }
            null -> {
                // Aux is free; infer it when the element's membership is forced one way.
                if (req) {
                    if (!state.pinBool(auxBoolVar, true)) return false
                } else if (!poss) {
                    if (!state.pinBool(auxBoolVar, false)) return false
                }
            }
        }
        return true
    }
}
