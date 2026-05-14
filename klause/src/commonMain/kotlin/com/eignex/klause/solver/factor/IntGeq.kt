package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.PropagationState
import com.eignex.klause.solver.SolverState

/** `x ≥ bound`. */
class IntGeq(
    val intVar: Int,
    val bound: Int,
) : Factor {

    override val boolVars: IntArray = EMPTY
    override val intVars: IntArray = intArrayOf(intVar)

    override fun initialize(state: SolverState, factorId: Int) {}

    override fun isViolated(state: SolverState, factorId: Int): Boolean =
        state.assignment.intValue(intVar) < bound

    override fun deltaIfIntSet(state: SolverState, factorId: Int, intVar: Int, newValue: Int): Int {
        val cur = state.assignment.intValue(this.intVar)
        val was = cur < bound
        val will = newValue < bound
        return (if (will) 1 else 0) - (if (was) 1 else 0)
    }

    override fun applyIntSet(state: SolverState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val cur = state.assignment.intValue(this.intVar)
        val was = oldValue < bound
        val now = cur < bound
        return (if (now) 1 else 0) - (if (was) 1 else 0)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        state.tightenIntMin(intVar, bound)

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        val cur = state.assignment.intValue(intVar)
        if (cur < bound) {
            val clamped = state.problem.intDomains[intVar].clamp(bound)
            if (clamped != cur) sink.addIntSet(intVar, clamped)
        }
    }

    private companion object {
        val EMPTY: IntArray = IntArray(0)
    }
}
