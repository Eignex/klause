package com.eignex.klause.factor.arithmetic

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink

internal class RealArrayMinMaxInvariant(private val factor: RealArrayMinMax) : Invariant {
    private fun extreme(state: LocalSearchState, changed: Int = -1, value: Double = 0.0): Double {
        var selected = if (factor.max) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        for (operand in factor.xs) {
            val current = if (operand == changed) value else state.assignment.realValue(operand)
            if (!current.isFinite()) return Double.NaN
            selected = if (factor.max) maxOf(selected, current) else minOf(selected, current)
        }
        return selected
    }

    private fun degree(state: LocalSearchState, changed: Int = -1, value: Double = 0.0): Int {
        val selected = extreme(state, changed, value)
        val result = if (factor.result == changed) value else state.assignment.realValue(factor.result)
        return if (selected.isFinite() && result.isFinite() && selected == result) 0 else 1
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = degree(state) != 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = degree(state)

    override fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int =
        degree(state, realVar, newValue) - state.factorDegree[factorId]

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val selected = extreme(state)
        if (!selected.isFinite()) return
        val value = selected.coerceIn(state.problem.realLower[factor.result], state.problem.realUpper[factor.result])
        if (value.isFinite() && value != state.assignment.realValue(factor.result)) {
            sink.addRealSet(factor.result, value)
        }
    }
}
