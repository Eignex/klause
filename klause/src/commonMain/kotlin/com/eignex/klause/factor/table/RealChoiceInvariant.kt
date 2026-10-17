package com.eignex.klause.factor.table

import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink

internal class RealChoiceInvariant(private val factor: RealChoice) : Invariant {
    private fun selected(state: LocalSearchState, flip: Boolean = false): Int {
        val truth = state.assignment.boolValue(Lit.variable(factor.condition)) != flip
        return if (Lit.evaluate(factor.condition, truth)) factor.whenTrue else factor.whenFalse
    }

    private fun degree(state: LocalSearchState, flip: Boolean = false, changed: Int = -1, value: Double = 0.0): Int {
        fun real(variable: Int): Double = if (variable == changed) value else state.assignment.realValue(variable)
        val input = real(selected(state, flip))
        val output = real(factor.result)
        return if (input.isFinite() && output.isFinite() && input == output) 0 else 1
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = degree(state) != 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = degree(state)

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        if (boolVar == Lit.variable(factor.condition)) degree(state, flip = true) - state.factorDegree[factorId] else 0

    override fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int =
        degree(state, changed = realVar, value = newValue) - state.factorDegree[factorId]

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (degree(state) == 0) return
        sink.addBoolFlip(Lit.variable(factor.condition))
        val entry = selected(state)
        val lower = state.problem.realLower
        val upper = state.problem.realUpper
        val output = state.assignment.realValue(entry).coerceIn(lower[factor.result], upper[factor.result])
        if (output.isFinite()) sink.addRealSet(factor.result, output)
        val input = state.assignment.realValue(factor.result).coerceIn(lower[entry], upper[entry])
        if (input.isFinite()) sink.addRealSet(entry, input)
    }
}
