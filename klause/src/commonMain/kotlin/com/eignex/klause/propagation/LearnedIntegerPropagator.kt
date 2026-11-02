package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.arithmetic.internals.collectLinearDirAntecedents
import com.eignex.klause.factor.arithmetic.internals.propagateLinearBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.EmptyIntArray

internal class LearnedIntegerPropagator(
    val row: IntegerConflictRow,
    private val cardinalityLimit: Int = Int.MIN_VALUE,
) : LearnedPropagator {
    val isCardinality: Boolean get() = cardinalityLimit != Int.MIN_VALUE
    // Admission owns this propagator, so its cache owner can unlink it without scanning other rows.
    internal var retentionSlot = -1
    override val boolVars: IntArray = if (row.guard == Lit.NONE) EmptyIntArray else intArrayOf(Lit.variable(row.guard))
    override val intVars: IntArray = row.variables
    private val linear = LinearPropagator(boolVars, intVars, row.coefficients, row.variables, LinearOp.LE, row.bound)
    override val initialIntEventWatches: IntArray = linear.initialIntEventWatches

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (!state.integerGuardTrueAt(row.guard)) return true
        if (isCardinality) return propagateCardinality(state)
        // Eager reasons retain their historical atoms without a compactable lazy factor id.
        return propagateLinearBounds(state, row.coefficients, row.variables, LinearOp.LE, row.bound,
            extraLit = row.guard xor 1, includeExtraLit = row.guard != Lit.NONE, factorId = -1)
    }

    private fun propagateCardinality(state: PropagationState): Boolean {
        var trueTerms = 0
        var unfixed = false
        for (index in row.variables.indices) {
            state.work++
            val domain = state.intDomains[row.variables[index]]
            if (if (row.coefficients[index] > 0L) domain.min == 1L else domain.max == 0L) trueTerms++
            unfixed = unfixed || domain.min < domain.max
        }
        if (trueTerms > cardinalityLimit) return false
        if (trueTerms != cardinalityLimit || !unfixed) return true
        // For a binary unit row the driving endpoints stay unchanged when every
        // remaining term is forced false, so all pins can own one eager reason.
        val reason = if (state.currentLevel == 0) null else collectLinearDirAntecedents(
            state, row.coefficients, row.variables, excludeIdx = -1, extraLit = row.guard xor 1,
            includeExtraLit = row.guard != Lit.NONE, useLo = true,
        )
        for (index in row.variables.indices) {
            state.work++
            val variable = row.variables[index]
            val domain = state.intDomains[variable]
            if (domain.min == domain.max) continue
            val propagated = if (row.coefficients[index] > 0L) state.tightenIntMax(variable, 0L, reason)
            else state.tightenIntMin(variable, 1L, reason)
            if (!propagated) return false
        }
        return true
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        linear.conflictReason(state, factorId)?.let { reason ->
            if (row.guard == Lit.NONE) reason else reason + (row.guard xor 1)
        }
}

internal fun PropagationState.learnedCardinalityLimit(row: IntegerConflictRow): Int {
    if (incremental || row.bound !in -row.variables.size.toLong()..row.variables.size.toLong()) return Int.MIN_VALUE
    var limit = row.bound.toInt()
    val bounds = problem.intBounds
    for (index in row.variables.indices) {
        work++
        val coefficient = row.coefficients[index]
        val variable = row.variables[index]
        if ((coefficient != 1L && coefficient != -1L) || !bounds.hasLower(variable) || !bounds.hasUpper(variable) ||
            bounds.lower(variable) != 0L || bounds.upper(variable) != 1L) return Int.MIN_VALUE
        if (coefficient < 0L) limit++
    }
    return limit
}
