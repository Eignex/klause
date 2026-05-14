package com.eignex.klause.solver.factor

import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.propagation.PropagationState
import com.eignex.klause.solver.localsearch.SolverState

/**
 * Reified integer comparison: `auxBoolVar ↔ (intVal ⟨op⟩ bound)`. Created by the compiler
 * when an [IntCompare] appears non-top-level (inside an Or, Implies, Iff, etc) so the rest
 * of the Tseitin lowering can treat its truth as a Boolean literal.
 */
class ReifiedIntCompare(
    val auxBoolVar: Int,
    val intVar: Int,
    val op: IntCmpOp,
    val bound: Int,
) : Factor {

    override val boolVars: IntArray = intArrayOf(auxBoolVar)
    override val intVars: IntArray = intArrayOf(intVar)

    override fun initialize(state: SolverState, factorId: Int) {}

    override fun isViolated(state: SolverState, factorId: Int): Boolean {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = cmpHolds(state.assignment.intValue(intVar))
        return aux != holds
    }

    override fun deltaIfBoolFlipped(state: SolverState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = cmpHolds(state.assignment.intValue(intVar))
        val wasViolated = aux != holds
        return if (wasViolated) -1 else +1
    }

    override fun deltaIfIntSet(state: SolverState, factorId: Int, intVar: Int, newValue: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val cur = state.assignment.intValue(this.intVar)
        val wasViolated = aux != cmpHolds(cur)
        val willViolate = aux != cmpHolds(newValue)
        return (if (willViolate) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun applyBoolFlip(state: SolverState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val holds = cmpHolds(state.assignment.intValue(intVar))
        // After the flip, equivalence flips: was aux != holds becomes aux == holds.
        val nowViolated = aux != holds
        return if (nowViolated) +1 else -1
    }

    override fun applyIntSet(state: SolverState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val cur = state.assignment.intValue(this.intVar)
        val wasViolated = aux != cmpHolds(oldValue)
        val nowViolated = aux != cmpHolds(cur)
        return (if (nowViolated) 1 else 0) - (if (wasViolated) 1 else 0)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val d = state.intDomains[intVar]
        val alwaysHolds = cmpHolds(d.min) && cmpHolds(d.max) && when (op) {
            // For LE/LT/GE/GT monotone — endpoints determine "always holds".
            IntCmpOp.LE, IntCmpOp.LT, IntCmpOp.GE, IntCmpOp.GT -> true
            // EQ "always" only when singleton matching bound; NE "always" only when bound not in domain.
            IntCmpOp.EQ -> d.min == bound && d.max == bound
            IntCmpOp.NE -> bound !in d.min..d.max
        }
        val neverHolds = !alwaysHolds && when (op) {
            IntCmpOp.LE -> d.min > bound
            IntCmpOp.LT -> d.min >= bound
            IntCmpOp.GE -> d.max < bound
            IntCmpOp.GT -> d.max <= bound
            IntCmpOp.EQ -> bound !in d.min..d.max
            IntCmpOp.NE -> d.min == bound && d.max == bound
        }
        if (alwaysHolds) return state.pinBool(auxBoolVar, true)
        if (neverHolds) return state.pinBool(auxBoolVar, false)

        val aux = state.boolValues[auxBoolVar] ?: return true
        // aux pinned: tighten domain to match (aux ⇒ holds) or (¬aux ⇒ ¬holds).
        return if (aux) tightenForHolds(state) else tightenForNotHolds(state, d)
    }

    private fun tightenForHolds(state: PropagationState): Boolean = when (op) {
        IntCmpOp.LE -> state.tightenIntMax(intVar, bound)
        IntCmpOp.LT -> state.tightenIntMax(intVar, bound - 1)
        IntCmpOp.GE -> state.tightenIntMin(intVar, bound)
        IntCmpOp.GT -> state.tightenIntMin(intVar, bound + 1)
        IntCmpOp.EQ -> state.setInt(intVar, bound)
        IntCmpOp.NE -> {
            val d = state.intDomains[intVar]
            when {
                d.min == bound -> state.tightenIntMin(intVar, bound + 1)
                d.max == bound -> state.tightenIntMax(intVar, bound - 1)
                else -> true
            }
        }
    }

    private fun tightenForNotHolds(state: PropagationState, d: com.eignex.klause.solver.IntDomain): Boolean = when (op) {
        IntCmpOp.LE -> state.tightenIntMin(intVar, bound + 1)
        IntCmpOp.LT -> state.tightenIntMin(intVar, bound)
        IntCmpOp.GE -> state.tightenIntMax(intVar, bound - 1)
        IntCmpOp.GT -> state.tightenIntMax(intVar, bound)
        IntCmpOp.EQ -> when {
            d.min == bound -> state.tightenIntMin(intVar, bound + 1)
            d.max == bound -> state.tightenIntMax(intVar, bound - 1)
            else -> true
        }
        IntCmpOp.NE -> state.setInt(intVar, bound)
    }

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        val aux = state.assignment.boolValue(auxBoolVar)
        val cur = state.assignment.intValue(intVar)
        if (aux == cmpHolds(cur)) return
        sink.addBoolFlip(auxBoolVar)
        val d = state.problem.intDomains[intVar]
        for (target in targetsForCmp(aux)) {
            val clamped = d.clamp(target)
            if (clamped == cur) continue
            if ((aux) == cmpHolds(clamped)) sink.addIntSet(intVar, clamped)
        }
    }

    private fun cmpHolds(value: Int): Boolean = when (op) {
        IntCmpOp.LE -> value <= bound
        IntCmpOp.LT -> value < bound
        IntCmpOp.GE -> value >= bound
        IntCmpOp.GT -> value > bound
        IntCmpOp.EQ -> value == bound
        IntCmpOp.NE -> value != bound
    }

    private fun targetsForCmp(wantHolds: Boolean): IntArray = when (op) {
        IntCmpOp.LE -> if (wantHolds) intArrayOf(bound) else intArrayOf(bound + 1)
        IntCmpOp.LT -> if (wantHolds) intArrayOf(bound - 1) else intArrayOf(bound)
        IntCmpOp.GE -> if (wantHolds) intArrayOf(bound) else intArrayOf(bound - 1)
        IntCmpOp.GT -> if (wantHolds) intArrayOf(bound + 1) else intArrayOf(bound)
        IntCmpOp.EQ -> if (wantHolds) intArrayOf(bound) else intArrayOf(bound - 1, bound + 1)
        IntCmpOp.NE -> if (wantHolds) intArrayOf(bound - 1, bound + 1) else intArrayOf(bound)
    }
}
