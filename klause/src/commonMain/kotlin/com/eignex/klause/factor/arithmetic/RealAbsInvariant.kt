package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import kotlin.math.abs
import kotlin.math.ceil

internal class RealAbsInvariant(private val operand: Int, private val result: Int) : Invariant {
    private fun degree(input: Double, output: Double, cap: Int): Int {
        val residual = abs(abs(input) - output)
        if (!residual.isFinite()) return cap
        if (residual <= TOLERANCE) return 0
        return compressViolation(maxOf(1L, ceil(residual / UNIT).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong()), cap)
    }

    private fun score(state: LocalSearchState, changed: Int = -1, value: Double = 0.0): Int = degree(
        if (changed == operand) value else state.assignment.realValue(operand),
        if (changed == result) value else state.assignment.realValue(result),
        state.violationSoftCap,
    )

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = score(state) != 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = score(state)

    override fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int =
        score(state, realVar, newValue) - score(state)

    override fun applyRealSet(state: LocalSearchState, factorId: Int, realVar: Int, oldValue: Double): Int =
        score(state) - score(state, realVar, oldValue)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val input = state.assignment.realValue(operand)
        val output = state.assignment.realValue(result)
        fun propose(variable: Int, value: Double) {
            if (value.isFinite() && value != state.assignment.realValue(variable) &&
                value >= state.problem.realLower[variable] && value <= state.problem.realUpper[variable] &&
                score(state, variable, value) <= score(state)
            ) sink.addRealSet(variable, value)
        }
        propose(result, abs(input))
        if (output >= 0.0) {
            propose(operand, output)
            if (output != 0.0) propose(operand, -output)
        }
        if (operand == result && input < 0.0) propose(operand, 0.0)
    }

    private companion object {
        const val TOLERANCE = 1e-9
        const val UNIT = 1e-6
    }
}
