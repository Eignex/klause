package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact

internal class GridArrayMinMaxInvariant(private val factor: GridArrayMinMax) : Invariant {
    private val bounds = (1 until factor.coordinates.size).map { factor.comparison(it, equality = false) }
    private val equalities = (1 until factor.coordinates.size).map { factor.comparison(it, equality = true) }

    private fun holds(row: Linear, state: LocalSearchState, changed: Int, value: Long): Boolean {
        val constants = checkNotNull(row.integralConstants)
        var activity = BIG_ZERO
        for (position in row.vars.indices) {
            val variable = row.vars[position]
            val current = if (variable == changed) value else state.assignment.intValue(variable)
            activity += constants.exactCoeff(position) * bigIntOf(current)
        }
        return if (row.op == LinearOp.EQ) activity == constants.exactBound
            else activity <= constants.exactBound
    }

    private fun degree(state: LocalSearchState, changed: Int = -1, value: Long = 0L): Int =
        if (bounds.all { holds(it, state, changed, value) } &&
            equalities.any { holds(it, state, changed, value) }) 0 else 1

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = degree(state) != 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = degree(state)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        degree(state, intVar, newValue) - state.factorDegree[factorId]

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val output = factor.coordinates[0]
        for (row in equalities) {
            val position = row.vars.indexOf(output)
            if (position < 0) continue
            val constants = checkNotNull(row.integralConstants)
            val coefficient = constants.exactCoeff(position)
            if (coefficient == BIG_ZERO) continue
            var residual = constants.exactBound
            for (index in row.vars.indices) if (index != position) {
                residual -= constants.exactCoeff(index) * bigIntOf(state.assignment.intValue(row.vars[index]))
            }
            val target = residual / coefficient
            if (!target.fitsLong()) continue
            val value = state.rootDomains[output].clamp(target.toLongExact())
            if (value != state.assignment.intValue(output)) sink.addChannelingIntSet(state, output, value)
        }
    }
}
