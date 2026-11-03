package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal enum class IntegerAssertion(val diagnostic: String) {
    ASSERTING("asserting"),
    GUARD_UNAVAILABLE("guard-unavailable"),
    CONFLICTING("conflicting"),
    RAW_BOUND_UNAVAILABLE("raw-bound-unavailable"),
    NO_TIGHTENING("no-tightening"),
}

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

    fun assertsAt(state: PropagationState, trail: Int): Boolean =
        assertionAt(state, trail) == IntegerAssertion.ASSERTING

    fun assertionAt(state: PropagationState, trail: Int): IntegerAssertion {
        if (!state.integerGuardTrueAt(guard, trail)) return IntegerAssertion.GUARD_UNAVAILABLE
        val domains = Array(variables.size) {
            visit(state)
            domain(state, variables[it], trail)
        }
        val contributions = LongArray(variables.size)
        var minimum = 0L
        for (index in variables.indices) {
            val endpoint = if (coefficients[index] > 0L) domains[index].min else domains[index].max
            val contribution = mulExact(coefficients[index], endpoint)
            contributions[index] = contribution
            minimum = addExact(minimum, contribution)
        }
        if (minimum > bound) return IntegerAssertion.CONFLICTING
        var unavailableRawBound = false
        for (index in variables.indices) {
            visit(state)
            val coefficient = coefficients[index]
            val residual = subExact(bound, subExact(minimum, contributions[index]))
            val next = if (coefficient > 0L) IntegerConflictRow.floorDivide(residual, coefficient)
                else IntegerConflictRow.ceilDivide(residual, coefficient)
            val domain = domains[index]
            val tightens = if (coefficient > 0L) next < domain.max else next > domain.min
            // Admission certifies a raw bound assertion, without relying on sparse snapping.
            if (tightens && state.inDomainAt(variables[index], next, trail, domain)) return IntegerAssertion.ASSERTING
            if (tightens) unavailableRawBound = true
        }
        return if (unavailableRawBound) IntegerAssertion.RAW_BOUND_UNAVAILABLE else IntegerAssertion.NO_TIGHTENING
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
