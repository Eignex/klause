package com.eignex.klause.solver.factor

import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.SolverState

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
    override val isHard: Boolean = true,
    override val weight: Double = 1.0,
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
