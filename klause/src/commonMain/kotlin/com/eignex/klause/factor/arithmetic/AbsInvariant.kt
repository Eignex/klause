package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.distance
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import kotlin.math.abs

internal class AbsInvariant(private val operand: Int, private val result: Int) : Invariant {
    private fun residual(input: Long, output: Long): Long =
        if (input == Long.MIN_VALUE) Long.MAX_VALUE else distance(abs(input), output)

    private fun score(state: LocalSearchState, changed: Int = -1, value: Long = 0L): Int = compressViolation(
        residual(
            if (changed == operand) value else state.assignment.intValue(operand),
            if (changed == result) value else state.assignment.intValue(result),
        ),
        state.violationSoftCap,
    )

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        residual(state.assignment.intValue(operand), state.assignment.intValue(result)) != 0L

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = score(state)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        score(state, intVar, newValue) - score(state)

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int =
        score(state) - score(state, intVar, oldValue)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val input = state.assignment.intValue(operand)
        val output = state.assignment.intValue(result)
        fun propose(variable: Int, value: Long) {
            if (value != state.assignment.intValue(variable) && value in state.rootDomains[variable] &&
                score(state, variable, value) <= score(state)
            ) {
                sink.addChannelingIntSet(state, variable, value)
            }
        }
        if (input != Long.MIN_VALUE) propose(result, abs(input))
        if (output >= 0L) {
            propose(operand, output)
            if (output != 0L) propose(operand, -output)
        }
        if (operand == result && input < 0L) propose(operand, state.rootDomains[operand].clamp(0L))
    }
}
