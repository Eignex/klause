package com.eignex.klause.factor.arithmetic

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import kotlin.math.abs
import kotlin.math.pow

internal class RealPowerInvariant(
    private val base: Int,
    private val exponent: Int,
    private val result: Int,
) : Invariant {
    private fun score(state: LocalSearchState, changed: Int = -1, value: Double = 0.0): Int {
        val input = if (changed == base) value else state.assignment.realValue(base)
        val output = if (changed == result) value else state.assignment.realValue(result)
        if (!input.isFinite()) return state.violationSoftCap
        return numericalEqualityDegree(input.pow(exponent), output, state.violationSoftCap)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = score(state) != 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = score(state)

    override fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int =
        score(state, realVar, newValue) - score(state)

    override fun applyRealSet(state: LocalSearchState, factorId: Int, realVar: Int, oldValue: Double): Int =
        score(state) - score(state, realVar, oldValue)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val input = state.assignment.realValue(base)
        val output = state.assignment.realValue(result)
        val before = score(state)
        fun propose(variable: Int, value: Double) {
            if (value.isFinite() && value != state.assignment.realValue(variable) &&
                value >= state.problem.realLower[variable] && value <= state.problem.realUpper[variable] &&
                score(state, variable, value) < before
            ) sink.addRealSet(variable, value)
        }
        propose(result, input.pow(exponent))
        if (exponent > 0 && (output >= 0.0 || exponent and 1 != 0)) {
            val magnitude = abs(output).pow(1.0 / exponent)
            propose(base, if (output < 0.0) -magnitude else magnitude)
            if (output > 0.0 && exponent and 1 == 0) propose(base, -magnitude)
        }
        if (base == result) {
            if (exponent > 0) propose(base, 0.0)
            propose(base, 1.0)
            if (exponent and 1 != 0) propose(base, -1.0)
        }
    }
}
