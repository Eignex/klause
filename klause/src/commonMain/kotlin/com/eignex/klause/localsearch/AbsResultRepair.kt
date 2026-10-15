package com.eignex.klause.localsearch

import com.eignex.klause.solver.objective.FunctionalObjective

internal class AbsResultRepair(private val state: LocalSearchState, private val sink: MoveSink) {
    fun propose(variable: Int, value: Long) {
        propose(variable, value, 0)
    }

    private fun propose(variable: Int, value: Long, depth: Int) {
        if (depth >= MAX_DEPTH || value < 0L || value !in state.rootDomains[variable] ||
            !sink.allowsDefinedInt(variable)
        ) return
        val definition = state.invariants?.intDefinition(variable) as? FunctionalObjective.Abs ?: return
        val input = definition.a.varId
        if (input < 0) return
        fun candidate(target: Long) {
            if (target !in state.rootDomains[input]) return
            if (state.invariants?.intDefinition(input) is FunctionalObjective.Abs) {
                propose(input, target, depth + 1)
            } else if (target != state.assignment.intValue(input)) {
                sink.addChannelingIntSet(state, input, target)
            }
        }
        candidate(value)
        if (value != 0L) candidate(-value)
    }

    private companion object {
        const val MAX_DEPTH = 16
    }
}
