package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.ceilingOrNull
import com.eignex.klause.ir.floorOrNull
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import kotlin.math.abs

internal class AbsPropagator(private val operand: Int, private val result: Int) : Propagator {
    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (operand == result) return state.tightenIntMin(operand, 0L)
        if (!state.tightenIntMin(result, 0L)) return false
        if (!state.tightenIntMin(operand, -state.intDomains[result].max) ||
            !state.tightenIntMax(operand, state.intDomains[result].max)
        ) {
                return false
            }
        val input = state.intDomains[operand]
        val closestPositive = input.ceilingOrNull(0L)
        val closestNegative = input.floorOrNull(0L)
        val lower = minOf(closestPositive ?: Long.MAX_VALUE, closestNegative?.let { -it } ?: Long.MAX_VALUE)
        val upper = maxOf(abs(input.min), abs(input.max))
        if (!state.tightenIntMin(result, lower) || !state.tightenIntMax(result, upper)) return false
        val output = state.intDomains[result]
        if (input.min >= 0L && !state.tightenIntMin(operand, output.min)) return false
        if (input.max <= 0L && !state.tightenIntMax(operand, -output.min)) return false
        val inputs = state.intDomains[operand].spanOrNull(FILTER_LIMIT)
        if (inputs != null) {
            for (i in 0 until inputs.size) {
                val value = inputs.valueAt(i)
                if (abs(value) !in state.intDomains[result] && !state.excludeIntValue(operand, value)) return false
            }
        }
        val outputs = state.intDomains[result].spanOrNull(FILTER_LIMIT)
        if (outputs != null) {
            for (i in 0 until outputs.size) {
                val value = outputs.valueAt(i)
                val domain = state.intDomains[operand]
                if (value !in domain && -value !in domain && !state.excludeIntValue(result, value)) return false
            }
        }
        return true
    }

    private companion object {
        const val FILTER_LIMIT = 1024L
    }
}
