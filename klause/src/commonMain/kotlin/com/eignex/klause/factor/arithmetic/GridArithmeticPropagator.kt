package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.collectHoleAndBoundAntecedents
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator

internal class GridArithmeticPropagator(private val relation: GridArithmetic) : Propagator {
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        collectHoleAndBoundAntecedents(state, relation.intVars)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        for (coordinate in relation.inputs + relation.output) {
            if (!state.tightenIntMin(coordinate.variable, 0L) ||
                !state.tightenIntMax(coordinate.variable, coordinate.buckets - 1L)) return false
        }
        val inputs = relation.inputs.map { it.variable }.distinct()
        var pairs = 1L
        val values = inputs.map { variable ->
            val span = state.intDomains[variable].spanOrNull(FILTER_LIMIT) ?: return true
            pairs *= span.size
            if (pairs > FILTER_LIMIT) return true
            LongArray(span.size) { span.valueAt(it) }
        }
        val supports = relation.intVars.associateWith { HashSet<Long>() }
        val assignment = HashMap<Int, Long>()
        fun visit(at: Int) {
            if (at < inputs.size) {
                for (value in values[at]) {
                    assignment[inputs[at]] = value
                    visit(at + 1)
                }
                return
            }
            state.work++
            val image = relation.image(assignment::getValue) ?: return
            val output = relation.output.variable
            if (image !in state.intDomains[output] || (output in assignment && assignment[output] != image)) return
            for ((variable, value) in assignment) supports.getValue(variable).add(value)
            supports.getValue(output).add(image)
        }
        visit(0)
        val reason = collectHoleAndBoundAntecedents(state, relation.intVars)
        for ((variable, supported) in supports) {
            if (supported.isEmpty()) return false
            if (!state.tightenIntMin(variable, supported.min(), reason) ||
                !state.tightenIntMax(variable, supported.max(), reason)) return false
            val span = state.intDomains[variable].spanOrNull(FILTER_LIMIT) ?: continue
            val candidates = LongArray(span.size) { span.valueAt(it) }
            for (value in candidates) {
                if (value !in supported && !state.excludeIntValue(variable, value, reason)) return false
            }
        }
        return true
    }

    private companion object {
        const val FILTER_LIMIT = 4096L
    }
}
