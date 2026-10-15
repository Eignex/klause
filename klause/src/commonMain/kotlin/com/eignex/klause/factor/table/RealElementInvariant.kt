package com.eignex.klause.factor.table

import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink

internal class RealElementInvariant(private val factor: RealElement) : Invariant {
    private fun degree(state: LocalSearchState, index: Long, changed: Int = -1, value: Double = 0.0): Int {
        val position = factor.position(index) ?: return 1
        fun real(variable: Int): Double = if (variable == changed) value else state.assignment.realValue(variable)
        val selected = if (factor.arrIsVars) real(factor.entries[position]) else factor.constants[position]
        val result = real(factor.result)
        return if (selected.isFinite() && result.isFinite() && selected == result) 0 else 1
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = violationDegree(state, factorId) != 0

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degree(state, state.assignment.intValue(factor.idx))

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        if (intVar == factor.idx) degree(state, newValue) - state.factorDegree[factorId] else 0

    override fun deltaIfRealSet(state: LocalSearchState, factorId: Int, realVar: Int, newValue: Double): Int =
        degree(state, state.assignment.intValue(factor.idx), realVar, newValue) - state.factorDegree[factorId]

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (violationDegree(state, factorId) == 0 || factor.size == 0) return
        val current = state.assignment.intValue(factor.idx)
        val domain = state.rootDomains[factor.idx]
        val first = factor.indexOffset.toLong()
        val target = domain.clamp(current.coerceIn(first, first + factor.size - 1))
        if (factor.position(target) != null && target != current) sink.addChannelingIntSet(state, factor.idx, target)
        if (current < domain.max) sink.addChannelingIntSet(state, factor.idx, domain.higher(current))
        if (current > domain.min) sink.addChannelingIntSet(state, factor.idx, domain.lower(current))
        val position = factor.position(current) ?: return
        val selected = if (factor.arrIsVars) state.assignment.realValue(factor.entries[position])
            else factor.constants[position]
        val lower = state.problem.realLower
        val upper = state.problem.realUpper
        val output = selected.coerceIn(lower[factor.result], upper[factor.result])
        if (output.isFinite()) sink.addRealSet(factor.result, output)
        if (factor.arrIsVars) {
            val entry = factor.entries[position]
            val input = state.assignment.realValue(factor.result).coerceIn(lower[entry], upper[entry])
            if (input.isFinite()) sink.addRealSet(entry, input)
        }
    }
}
