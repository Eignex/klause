package com.eignex.klause.localsearch

import com.eignex.klause.factor.arithmetic.powerPreimage
import com.eignex.klause.solver.objective.FunctionalObjective

internal class PowerResultRepair(private val state: LocalSearchState, private val sink: MoveSink) {
    fun propose(output: Int, target: Long) {
        fun solve(variable: Int, value: Long, depth: Int) {
            if (value !in state.rootDomains[variable] || depth > MAX_POWER_DEPTH) return
            val definition = state.invariants?.intDefinition(variable) as? FunctionalObjective.Power
            if (definition == null) {
                sink.addChannelingIntSet(state, variable, value)
                return
            }
            if (depth == MAX_POWER_DEPTH || !sink.allowsDefinedInt(variable) || state.assumptions.isFrozenInt(variable) ||
                definition.exponent == 0 || definition.base.varId < 0) return
            val base = definition.base.varId
            val domain = state.rootDomains[base]
            val segments = if (definition.exponent and 1 != 0) listOf(domain.min to domain.max) else
                listOf(domain.min to minOf(-1L, domain.max), maxOf(0L, domain.min) to domain.max)
            for ((lo, hi) in segments) {
                val roots = powerPreimage(lo, hi, definition.exponent, value, value) ?: continue
                solve(base, roots.first, depth + 1)
            }
        }
        solve(output, target, 0)
    }

    private companion object {
        const val MAX_POWER_DEPTH = 16
    }
}
