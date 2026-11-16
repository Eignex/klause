package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.distance
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink

internal class PowerInvariant(private val base: Int, private val exponent: Int, private val result: Int) : Invariant {
    private fun residual(input: Long, output: Long): Long =
        integerPowerOrNull(input, exponent)?.let { distance(it, output) } ?: Long.MAX_VALUE

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        residual(state.assignment.intValue(base), state.assignment.intValue(result)) != 0L

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int = degree(state, -1, 0L)

    private fun degree(state: LocalSearchState, variable: Int, value: Long): Int = compressViolation(
        residual(if (variable == base) value else state.assignment.intValue(base),
            if (variable == result) value else state.assignment.intValue(result)), state.violationSoftCap,
    )

    override fun deltaIfIntSet(state: LocalSearchState, factorId: Int, intVar: Int, newValue: Long): Int =
        degree(state, intVar, newValue) - degree(state, -1, 0L)

    override fun applyIntSet(state: LocalSearchState, factorId: Int, intVar: Int, oldValue: Long): Int =
        degree(state, -1, 0L) - degree(state, intVar, oldValue)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val before = degree(state, -1, 0L)
        if (before == 0) return
        fun propose(variable: Int, value: Long) {
            if (value in state.rootDomains[variable] && value != state.assignment.intValue(variable) &&
                degree(state, variable, value) <= before) sink.addChannelingIntSet(state, variable, value)
        }
        integerPowerOrNull(state.assignment.intValue(base), exponent)?.let { propose(result, it) }
        if (exponent == 0) return
        val domain = state.rootDomains[base]
        val output = state.assignment.intValue(result)
        val segments = if (exponent and 1 != 0) listOf(domain.min to domain.max) else
            listOf(domain.min to minOf(-1L, domain.max), maxOf(0L, domain.min) to domain.max)
        for ((lo, hi) in segments) {
            val roots = powerPreimage(lo, hi, exponent, output, output) ?: continue
            propose(base, roots.first)
            if (roots.second != roots.first) propose(base, roots.second)
        }
        super.proposeRepairMoves(state, factorId, sink)
    }
}
