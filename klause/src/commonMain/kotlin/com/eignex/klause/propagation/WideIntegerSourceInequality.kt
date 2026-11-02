package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.util.addExact
import com.eignex.klause.util.mulExact
import com.eignex.klause.util.subExact

internal const val INTEGER_SOURCE_TERMS = INTEGER_LEARNING_TERMS * 2
private const val INTEGER_SOURCE_VISITS = 4096

internal class IntegerSourceWork(
    private val state: PropagationState,
    private var remaining: Int = INTEGER_SOURCE_VISITS,
) {
    fun recordTransient(row: IntegerLearningInequality) {
        state.arithmeticLearningTrace?.recordTransient(row)
    }

    fun visit() {
        if (remaining-- <= 0) throw IntegerSourceWorkLimit()
        state.checkFiniteWork()
    }
}

internal class IntegerSourceWorkLimit : RuntimeException()

internal class WideIntegerSourceInequality private constructor(
    override val variables: IntArray,
    override val coefficients: LongArray,
    override val bound: Long,
    override val guard: Int,
    private val work: IntegerSourceWork,
) : IntegerLearningInequality {
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

    companion object {
        fun create(source: IntegerSourceRow, useLo: Boolean, work: IntegerSourceWork): WideIntegerSourceInequality? {
            val linear = source.linear
            if (linear.vars.size !in (INTEGER_LEARNING_TERMS + 1)..INTEGER_SOURCE_TERMS) return null
            for (index in linear.vars.indices) {
                work.visit()
                for (previous in 0 until index) {
                    work.visit()
                    if (linear.vars[previous] == linear.vars[index]) return null
                }
            }
            val coefficients = if (useLo) linear.coeffs else LongArray(linear.coeffs.size) {
                work.visit()
                subExact(0L, linear.coeffs[it])
            }
            work.visit()
            val bound = if (useLo) linear.bound else subExact(0L, linear.bound)
            return WideIntegerSourceInequality(linear.vars, coefficients, bound, source.guard, work)
        }
    }
}

internal fun IntegerLearningInequality.resolve(
    other: IntegerLearningInequality,
    variable: Int,
    work: IntegerSourceWork,
): IntegerConflictRow? = combine(other, variable, work, allowTransient = false) as? IntegerConflictRow

internal fun IntegerLearningInequality.resolveTransient(
    other: IntegerLearningInequality,
    variable: Int,
    work: IntegerSourceWork,
): IntegerLearningInequality? = combine(other, variable, work, allowTransient = true)

private fun IntegerLearningInequality.combine(
    other: IntegerLearningInequality,
    variable: Int,
    work: IntegerSourceWork,
    allowTransient: Boolean,
): IntegerLearningInequality? {
    if (this is IntegerConflictRow && other is IntegerConflictRow) return resolve(other, variable)
    if (guard != Lit.NONE && other.guard != Lit.NONE && guard != other.guard) return null
    val a = coefficient(variable)
    val b = other.coefficient(variable)
    if (a == 0L || b == 0L || (a > 0L) == (b > 0L)) return null
    val divisor = IntegerConflictRow.gcd(IntegerConflictRow.magnitude(a), IntegerConflictRow.magnitude(b))
    val left = IntegerConflictRow.magnitude(b) / divisor
    val right = IntegerConflictRow.magnitude(a) / divisor
    val terms = mutableMapOf<Int, Long>()
    for (side in 0..1) {
        val row = if (side == 0) this else other
        val multiplier = if (side == 0) left else right
        for (index in row.variables.indices) {
            work.visit()
            val ref = row.variables[index]
            val next = addExact(terms[ref] ?: 0L, mulExact(row.coefficients[index], multiplier))
            if (next == 0L) terms.remove(ref) else terms[ref] = next
        }
    }
    val limit = if (allowTransient) INTEGER_SOURCE_TERMS else INTEGER_LEARNING_TERMS
    if (terms.isEmpty() || terms.size > limit) return null
    val resultBound = addExact(mulExact(bound, left), mulExact(other.bound, right))
    val resultGuard = if (guard != Lit.NONE) guard else other.guard
    if (terms.size <= INTEGER_LEARNING_TERMS) return IntegerConflictRow.normalized(terms, resultBound, resultGuard)
    if (resultGuard != Lit.NONE) return null
    return TransientIntegerRow.normalized(terms, resultBound, work)?.also(work::recordTransient)
}
