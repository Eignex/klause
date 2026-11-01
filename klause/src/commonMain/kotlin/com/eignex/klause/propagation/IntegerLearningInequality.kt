package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal interface IntegerLearningInequality {
    val variables: IntArray
    val coefficients: LongArray
    val bound: Long
    val guard: Int

    fun visit(state: PropagationState) = state.checkFiniteWork()

    fun coefficient(variable: Int): Long

    fun indexOf(variable: Int): Int = variables.indexOf(variable)

    fun minimum(state: PropagationState, trail: Int? = null): Long = activity(state, trail, maximum = false)

    fun maximum(state: PropagationState): Long = activity(state, null, maximum = true)

    private fun activity(state: PropagationState, trail: Int?, maximum: Boolean): Long {
        var sum = 0L
        for (i in variables.indices) {
            visit(state)
            val domain = domain(state, variables[i], trail)
            val coefficient = coefficients[i]
            val endpoint = if ((coefficient > 0L) != maximum) domain.min else domain.max
            sum = addExact(sum, mulExact(coefficient, endpoint))
        }
        return sum
    }

    fun boundFor(state: PropagationState, variable: Int, trail: Int): Long? {
        val pivot = indexOf(variable)
        if (pivot < 0) return null
        var other = 0L
        for (i in variables.indices) {
            visit(state)
            if (i == pivot) continue
            val domain = domain(state, variables[i], trail)
            val endpoint = if (coefficients[i] > 0L) domain.min else domain.max
            other = addExact(other, mulExact(coefficients[i], endpoint))
        }
        val residual = subExact(bound, other)
        val coefficient = coefficients[pivot]
        return if (coefficient > 0L) {
            IntegerConflictRow.floorDivide(residual, coefficient)
        } else {
            IntegerConflictRow.ceilDivide(residual, coefficient)
        }
    }

    private fun domain(state: PropagationState, variable: Int, trail: Int?): IntDomain =
        if (trail == null) state.intDomains[variable] else state.domainAt(variable, trail)
}
