package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal class TransientIntegerRow private constructor(
    override val variables: IntArray,
    override val coefficients: LongArray,
    override val bound: Long,
    private val work: IntegerSourceWork,
) : IntegerLearningInequality {
    override val guard: Int get() = Lit.NONE

    override fun visit(state: PropagationState) = work.visit()

    override fun coefficient(variable: Int): Long {
        val index = indexOf(variable)
        return if (index < 0) 0L else coefficients[index]
    }

    override fun indexOf(variable: Int): Int {
        for (index in variables.indices) {
            work.visit()
            if (variables[index] == variable) return index
        }
        return -1
    }

    fun declaredBoundConsequence(state: PropagationState): IntegerConflictRow? = try {
        val bounds = state.problem.intBounds
        val terms = mutableMapOf<Int, Long>()
        var residual = bound
        var removed = 0
        var remaining = variables.size - INTEGER_LEARNING_TERMS
        var preserved = false
        state.arithmeticLearningTrace?.record("integer.retained.box.attempt")
        for (index in variables.indices) {
            work.visit()
            val variable = variables[index]
            val coefficient = coefficients[index]
            val domain = state.intDomains[variable]
            val available = if (coefficient > 0L) bounds.hasLower(variable) else bounds.hasUpper(variable)
            val current = if (coefficient > 0L) domain.min else domain.max
            val declared = if (!available) null else if (coefficient > 0L) bounds.lower(variable)
                else bounds.upper(variable)
            // Immutable model bounds are axioms; root pins can be call-scoped.
            if (declared != null && current == declared && remaining > 0) {
                residual = subExact(residual, mulExact(coefficient, declared))
                removed++
                remaining--
            } else {
                terms[variable] = coefficient
                if (declared != null && current == declared) preserved = true
            }
        }
        if (removed == 0 || terms.size > INTEGER_LEARNING_TERMS) {
            state.arithmeticLearningTrace?.record(if (removed == 0) "integer.retained.box.reject.no-extremum"
                else "integer.retained.box.reject.width")
            null
        } else {
            repeat(terms.size * 4 + 8) { work.visit() }
            if (preserved) state.arithmeticLearningTrace?.record("integer.retained.box.preserved")
            IntegerConflictRow.normalized(terms, residual, Lit.NONE)?.derivedFromDeclaredBounds(removed,
                if (preserved) DECLARED_BOUND_PRESERVED_TERMS else 0)
        }
    } catch (_: CheckedLongOverflowException) {
        state.arithmeticLearningTrace?.record("integer.retained.box.reject.overflow")
        null
    }

    companion object {
        fun normalized(terms: Map<Int, Long>, bound: Long, work: IntegerSourceWork): TransientIntegerRow? {
            if (terms.size !in (INTEGER_LEARNING_TERMS + 1)..INTEGER_SOURCE_TERMS) return null
            var divisor = 0L
            for (coefficient in terms.values) {
                work.visit()
                if (coefficient == 0L || coefficient == Long.MIN_VALUE) return null
                divisor = IntegerConflictRow.gcd(divisor, IntegerConflictRow.magnitude(coefficient))
            }
            if (divisor == 0L) return null
            val variables = terms.keys.sorted().toIntArray()
            val coefficients = LongArray(variables.size) {
                work.visit()
                terms.getValue(variables[it]) / divisor
            }
            return TransientIntegerRow(variables, coefficients, IntegerConflictRow.floorDivide(bound, divisor), work)
        }
    }
}
