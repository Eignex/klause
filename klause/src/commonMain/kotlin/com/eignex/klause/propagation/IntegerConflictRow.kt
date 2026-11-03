package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal const val INTEGER_LEARNING_TERMS = 16
internal const val DECLARED_BOUND_EARLY = 1
internal const val DECLARED_BOUND_PRESERVED_TERMS = 2

internal class IntegerConflictRow private constructor(
    override val variables: IntArray,
    override val coefficients: LongArray,
    override val bound: Long,
    override val guard: Int = Lit.NONE,
    val transientSteps: Int = 0,
    val retainedSources: Int = 0,
    val hasNonOwnerSource: Boolean = false,
    val hasNonOwnerOriginalSource: Boolean = false,
    val declaredBoundTerms: Int = 0,
    val declaredBoundModes: Int = 0,
    val hasSlackOriginalSource: Boolean = false,
    val hasDuplicateContinuation: Boolean = false,
    val hasBoundImplication: Boolean = false,
) : IntegerLearningInequality {
    override fun coefficient(variable: Int): Long {
        val index = variables.indexOf(variable)
        return if (index < 0) 0L else coefficients[index]
    }

    fun derivedThroughTransient(steps: Int): IntegerConflictRow = if (steps == 0) this else
        IntegerConflictRow(variables, coefficients, bound, guard, steps, retainedSources, hasNonOwnerSource,
            hasNonOwnerOriginalSource, declaredBoundTerms, declaredBoundModes, hasSlackOriginalSource,
            hasDuplicateContinuation, hasBoundImplication)

    fun derivedFromRetainedSources(
        sources: Int,
        nonOwnerSource: Boolean = false,
        nonOwnerOriginalSource: Boolean = false,
        slackOriginalSource: Boolean = false,
        duplicateContinuation: Boolean = false,
        boundImplication: Boolean = false,
    ): IntegerConflictRow = if (sources == 0 && !nonOwnerSource && !nonOwnerOriginalSource &&
        !slackOriginalSource && !duplicateContinuation && !boundImplication) {
        this
    } else {
        IntegerConflictRow(variables, coefficients, bound, guard, transientSteps, sources,
            hasNonOwnerSource || nonOwnerSource, hasNonOwnerOriginalSource || nonOwnerOriginalSource,
            declaredBoundTerms, declaredBoundModes, hasSlackOriginalSource || slackOriginalSource,
            hasDuplicateContinuation || duplicateContinuation, hasBoundImplication || boundImplication)
    }

    fun derivedFromDeclaredBounds(terms: Int, modes: Int = 0): IntegerConflictRow = if (terms == 0) this else
        IntegerConflictRow(variables, coefficients, bound, guard, transientSteps, retainedSources,
            hasNonOwnerSource, hasNonOwnerOriginalSource, terms, declaredBoundModes or modes, hasSlackOriginalSource,
            hasDuplicateContinuation, hasBoundImplication)

    fun subsumes(other: IntegerConflictRow): Boolean =
        (guard == Lit.NONE || guard == other.guard) && bound <= other.bound &&
            variables.contentEquals(other.variables) && coefficients.contentEquals(other.coefficients)

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

        internal fun normalized(terms: Map<Int, Long>, bound: Long, guard: Int): IntegerConflictRow? {
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

        internal fun magnitude(value: Long): Long = if (value < 0L) subExact(0L, value) else value

        internal fun gcd(a: Long, b: Long): Long {
            var left = a
            var right = b
            while (right != 0L) {
                val remainder = left % right
                left = right
                right = remainder
            }
            return left
        }

        internal fun floorDivide(value: Long, divisor: Long): Long {
            if (divisor == -1L) return subExact(0L, value)
            val quotient = value / divisor
            return if (value % divisor != 0L && (value xor divisor) < 0L) subExact(quotient, 1L) else quotient
        }

        internal fun ceilDivide(value: Long, divisor: Long): Long {
            if (divisor == -1L) return subExact(0L, value)
            val quotient = value / divisor
            return if (value % divisor != 0L && (value xor divisor) >= 0L) addExact(quotient, 1L) else quotient
        }
    }
}
