package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.exclusionLiteral

internal class PowerPropagator(private val base: Int, private val exponent: Int, private val result: Int) : Propagator {
    private val variables = intArrayOf(base, result).distinct().toIntArray()

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val premises = state.composeIntVarAtomAntecedents(variables)
        if (exponent == 0) return state.tightenIntMin(result, 1L, premises) &&
            state.tightenIntMax(result, 1L, premises)
        val input = state.intDomains[base]
        val output = state.intDomains[result]
        val segments = if (exponent and 1 != 0) listOf(input.min to input.max) else
            listOf(input.min to minOf(-1L, input.max), maxOf(0L, input.min) to input.max)
        val supported = segments.mapNotNull { (lo, hi) ->
            powerPreimage(lo, hi, exponent, output.min, output.max) { state.work++ }
        }
        if (supported.isEmpty()) return false
        if (!state.tightenIntMin(base, supported.minOf { it.first }, premises) ||
            !state.tightenIntMax(base, supported.maxOf { it.second }, premises)) return false
        val narrowed = state.intDomains[base]
        val first = saturatedIntegerPower(narrowed.min, exponent)
        val last = saturatedIntegerPower(narrowed.max, exponent)
        val lower = if (exponent and 1 == 0 && narrowed.min <= 0 && narrowed.max >= 0) 0L else minOf(first, last)
        if (!state.tightenIntMin(result, lower, premises) ||
            !state.tightenIntMax(result, maxOf(first, last), premises)) return false
        val span = state.intDomains[base].spanOrNull(MAX_FILTER_VALUES)
        val values = span?.let { LongArray(it.size) { i -> it.valueAt(i) } }
        if (values != null) for (i in 0 until values.size) {
            state.work++
            val value = values[i]
            val image = integerPowerOrNull(value, exponent)
            if (image == null || image !in state.intDomains[result] || (base == result && image != value)) {
                val reason = if (image != null && image !in state.intDomains[result])
                    (premises ?: intArrayOf()) + absentLiteral(state, result, image) else premises
                if (!state.excludeIntValue(base, value, reason)) return false
            }
        }
        val resultSpan = state.intDomains[result].spanOrNull(MAX_FILTER_VALUES) ?: return true
        val results = LongArray(resultSpan.size) { resultSpan.valueAt(it) }
        for (i in 0 until results.size) {
            state.work++
            val value = results[i]
            val roots = if (exponent and 1 != 0) {
                listOfNotNull(
                    powerPreimage(Long.MIN_VALUE, Long.MAX_VALUE, exponent, value, value) { state.work++ }?.first,
                )
            } else listOfNotNull(
                powerPreimage(Long.MIN_VALUE, -1L, exponent, value, value) { state.work++ }?.first,
                powerPreimage(0L, Long.MAX_VALUE, exponent, value, value) { state.work++ }?.first,
            )
            if (roots.any { it in state.intDomains[base] && (base != result || it == value) }) continue
            val reason = (state.composeIntVarAtomAntecedents(variables) ?: intArrayOf()) +
                roots.filter { it !in state.intDomains[base] }.flatMap { absentLiteral(state, base, it).toList() }
            if (!state.excludeIntValue(result, value, reason)) return false
        }
        return true
    }

    private fun absentLiteral(state: PropagationState, variable: Int, value: Long): IntArray {
        if (value !in state.rootDomains[variable]) return intArrayOf()
        val literal = if (state.undoLogging) state.exclusionLiteral(variable, value, state.undo.size)
            else Lit.make(state.atomVarEq(variable, value), true)
        return if (literal == Lit.NONE) intArrayOf() else intArrayOf(literal)
    }

    private companion object {
        const val MAX_FILTER_VALUES = 256L
    }
}
