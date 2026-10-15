package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.distance
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink

internal class GridArithmeticInvariant(private val relation: GridArithmetic) : Invariant {
    private fun score(state: LocalSearchState, value: (Int) -> Long): Int {
        if (relation.inputs.any { !it.contains(value(it.variable)) } ||
            !relation.output.contains(value(relation.output.variable))) return state.violationSoftCap
        val image = relation.image(value) ?: return state.violationSoftCap
        return compressViolation(distance(image, value(relation.output.variable)), state.violationSoftCap)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        score(state, state.assignment::intValue) != 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = score(state, state.assignment::intValue)

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        score(state) { if (it == intVar) newValue else state.assignment.intValue(it) } -
            score(state, state.assignment::intValue)

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int =
        score(state, state.assignment::intValue) -
            score(state) { if (it == intVar) oldValue else state.assignment.intValue(it) }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val before = score(state, state.assignment::intValue)
        if (before == 0) return
        fun propose(variable: Int, value: Long) {
            if (value in state.rootDomains[variable] && value != state.assignment.intValue(variable) &&
                score(state) { if (it == variable) value else state.assignment.intValue(it) } < before
            ) sink.addChannelingIntSet(state, variable, value)
        }
        relation.image(state.assignment::intValue)?.let { propose(relation.output.variable, it) }
        for (variable in relation.inputs.map { it.variable }.distinct()) {
            val span = state.rootDomains[variable].spanOrNull(REPAIR_LIMIT) ?: continue
            for (index in 0 until span.size) propose(variable, span.valueAt(index))
        }
    }

    private companion object {
        const val REPAIR_LIMIT = 64L
    }
}
