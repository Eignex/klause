package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.MoveSink
import com.eignex.klause.solver.SolverState

/** `x = value`. */
class IntEq(
    val intVar: Int,
    val value: Int,
    override val isHard: Boolean = true,
    override val weight: Double = 1.0,
) : Factor {

    override val boolVars: IntArray = EMPTY
    override val intVars: IntArray = intArrayOf(intVar)

    override fun initialize(state: SolverState, factorId: Int) {}

    override fun isViolated(state: SolverState, factorId: Int): Boolean =
        state.assignment.intValue(intVar) != value

    override fun deltaIfIntSet(state: SolverState, factorId: Int, intVar: Int, newValue: Int): Int {
        val cur = state.assignment.intValue(this.intVar)
        val was = cur != value
        val will = newValue != value
        return (if (will) 1 else 0) - (if (was) 1 else 0)
    }

    override fun applyIntSet(state: SolverState, factorId: Int, intVar: Int, oldValue: Int): Int {
        val cur = state.assignment.intValue(this.intVar)
        val was = oldValue != value
        val now = cur != value
        return (if (now) 1 else 0) - (if (was) 1 else 0)
    }

    override fun proposeRepairMoves(state: SolverState, factorId: Int, sink: MoveSink) {
        if (state.assignment.intValue(intVar) != value) sink.addIntSet(intVar, value)
    }

    private companion object {
        val EMPTY: IntArray = IntArray(0)
    }
}
