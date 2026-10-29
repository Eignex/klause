package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal const val INTEGER_LEARNING_TERMS = 16

internal class IntegerConflictRow private constructor(
    val variables: IntArray,
    val coefficients: LongArray,
    val bound: Long,
    val guard: Int = Lit.NONE,
) {
    fun coefficient(variable: Int): Long {
        val index = variables.indexOf(variable)
        return if (index < 0) 0L else coefficients[index]
    }

    fun subsumes(other: IntegerConflictRow): Boolean =
        (guard == Lit.NONE || guard == other.guard) && bound <= other.bound &&
            variables.contentEquals(other.variables) && coefficients.contentEquals(other.coefficients)

    fun minimum(state: PropagationState, trail: Int? = null): Long = activity(state, trail, maximum = false)

    fun maximum(state: PropagationState): Long = activity(state, null, maximum = true)

    private fun activity(state: PropagationState, trail: Int?, maximum: Boolean): Long {
        var sum = 0L
        for (i in variables.indices) {
            state.checkFiniteWork()
            val domain = domain(state, variables[i], trail)
            val coefficient = coefficients[i]
            val endpoint = if ((coefficient > 0L) != maximum) domain.min else domain.max
            sum = addExact(sum, mulExact(coefficient, endpoint))
        }
        return sum
    }

    fun boundFor(state: PropagationState, variable: Int, trail: Int): Long? {
        val pivot = variables.indexOf(variable)
        if (pivot < 0) return null
        var other = 0L
        for (i in variables.indices) {
            state.checkFiniteWork()
            if (i == pivot) continue
            val domain = domain(state, variables[i], trail)
            val endpoint = if (coefficients[i] > 0L) domain.min else domain.max
            other = addExact(other, mulExact(coefficients[i], endpoint))
        }
        val residual = subExact(bound, other)
        val coefficient = coefficients[pivot]
        return if (coefficient > 0L) floorDivide(residual, coefficient) else ceilDivide(residual, coefficient)
    }

    fun assertsAt(state: PropagationState, trail: Int): Boolean {
        if (!state.integerGuardTrueAt(guard, trail)) return false
        val domains = Array(variables.size) {
            state.checkFiniteWork()
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
        if (minimum > bound) return false
        for (index in variables.indices) {
            state.checkFiniteWork()
            val coefficient = coefficients[index]
            val residual = subExact(bound, subExact(minimum, contributions[index]))
            val next = if (coefficient > 0L) floorDivide(residual, coefficient) else ceilDivide(residual, coefficient)
            val domain = domains[index]
            val tightens = if (coefficient > 0L) next < domain.max else next > domain.min
            // Admission certifies a raw bound assertion, without relying on sparse snapping.
            if (tightens && state.inDomainAt(variables[index], next, trail, domain)) return true
        }
        return false
    }

    fun resolve(other: IntegerConflictRow, variable: Int): IntegerConflictRow? {
        if (guard != Lit.NONE && other.guard != Lit.NONE && guard != other.guard) return null
        val a = coefficient(variable)
        val b = other.coefficient(variable)
        if (a == 0L || b == 0L || (a > 0L) == (b > 0L)) return null
        val divisor = gcd(magnitude(a), magnitude(b))
        val left = magnitude(b) / divisor
        val right = magnitude(a) / divisor
        val terms = mutableMapOf<Int, Long>()
        for (i in variables.indices) terms[variables[i]] = mulExact(coefficients[i], left)
        for (i in other.variables.indices) {
            val v = other.variables[i]
            val next = addExact(terms[v] ?: 0L, mulExact(other.coefficients[i], right))
            if (next == 0L) terms.remove(v) else terms[v] = next
        }
        return normalized(terms, addExact(mulExact(bound, left), mulExact(other.bound, right)),
            if (guard != Lit.NONE) guard else other.guard)
    }

    companion object {
        fun source(factor: LinearPropagator, useLo: Boolean, guard: Int = Lit.NONE): IntegerConflictRow? {
            if (factor.vars.size > INTEGER_LEARNING_TERMS) return null
            val terms = mutableMapOf<Int, Long>()
            for (i in factor.vars.indices) {
                val coefficient = if (useLo) factor.coeffs[i] else subExact(0L, factor.coeffs[i])
                if (coefficient == Long.MIN_VALUE) return null
                val variable = factor.vars[i]
                val next = addExact(terms[variable] ?: 0L, coefficient)
                if (next == 0L) terms.remove(variable) else terms[variable] = next
            }
            val bound = if (useLo) factor.bound else subExact(0L, factor.bound)
            return normalized(terms, bound, guard)
        }

        private fun normalized(terms: Map<Int, Long>, bound: Long, guard: Int): IntegerConflictRow? {
            if (terms.isEmpty() || terms.size > INTEGER_LEARNING_TERMS) return null
            var divisor = 0L
            for (coefficient in terms.values) {
                if (coefficient == Long.MIN_VALUE) return null
                divisor = gcd(divisor, magnitude(coefficient))
            }
            if (divisor == 0L) return null
            val variables = terms.keys.sorted().toIntArray()
            return IntegerConflictRow(variables, LongArray(variables.size) { terms.getValue(variables[it]) / divisor },
                floorDivide(bound, divisor), guard)
        }

        private fun domain(state: PropagationState, variable: Int, trail: Int?): IntDomain =
            if (trail == null) state.intDomains[variable] else state.domainAt(variable, trail)

        private fun magnitude(value: Long): Long = if (value < 0L) subExact(0L, value) else value

        private fun gcd(a: Long, b: Long): Long {
            var left = a
            var right = b
            while (right != 0L) {
                val remainder = left % right
                left = right
                right = remainder
            }
            return left
        }

        private fun floorDivide(value: Long, divisor: Long): Long {
            if (divisor == -1L) return subExact(0L, value)
            val quotient = value / divisor
            return if (value % divisor != 0L && (value xor divisor) < 0L) subExact(quotient, 1L) else quotient
        }

        private fun ceilDivide(value: Long, divisor: Long): Long {
            if (divisor == -1L) return subExact(0L, value)
            val quotient = value / divisor
            return if (value % divisor != 0L && (value xor divisor) >= 0L) addExact(quotient, 1L) else quotient
        }
    }
}
