package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.arithmetic.internals.explainLinearBound
import com.eignex.klause.factor.arithmetic.internals.linearLazyReason
import com.eignex.klause.factor.arithmetic.internals.propagateLinearBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.util.arrays.emptyIntArray
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.times
import com.eignex.util.numbers.toLongExact

internal class LearnedIntegerPropagator(val row: IntegerConflictRow) : LearnedPropagator {
    // Admission owns this propagator, so its cache owner can unlink it without scanning other rows.
    internal var retentionSlot = -1
    override val boolVars: IntArray =
        if (row.guard == Lit.NONE) emptyIntArray() else intArrayOf(Lit.variable(row.guard))
    override val intVars: IntArray = row.variables
    internal val compact: LinearPropagator? = compactRow()
    override val initialIntEventWatches: IntArray = compact?.initialIntEventWatches ?: IntArray(intVars.size * 2) {
        IntEvent.pack(intVars[it / 2], if (it % 2 == 0) IntEvent.LB_RAISED else IntEvent.UB_LOWERED)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        if (!state.integerGuardTrueAt(row.guard)) return true
        compact?.let {
            return propagateLinearBounds(state, it.coeffs, intVars, LinearOp.LE, it.bound,
                extraLit = row.guard xor 1, includeExtraLit = row.guard != Lit.NONE, factorId = factorId)
        }
        val minimum = row.minimum(state)
        if (minimum > row.bound) return false
        for (index in intVars.indices) {
            state.checkFiniteWork()
            val variable = intVars[index]
            val coefficient = row.coefficients[index]
            val domain = state.intDomains[variable]
            val lower = coefficient < BIG_ZERO
            state.checkIntegerScalarWork(coefficient, bigIntOf(if (lower) domain.max else domain.min))
            val contribution = coefficient * bigIntOf(if (lower) domain.max else domain.min)
            state.checkIntegerScalarWork(row.bound, minimum)
            val next = IntegerConflictRow.endpoint(row.bound - (minimum - contribution), coefficient)
            if (!next.fitsLong()) continue
            val value = next.toLongExact()
            if (if (lower) value <= domain.min else value >= domain.max) continue
            val reason = if (state.undoLogging) {
                linearLazyReason(state, factorId, index, useLo = true, slack = 0,
                    extraLit = row.guard xor 1, includeExtraLit = row.guard != Lit.NONE)
            } else explainRow(state, index, state.undo.size)
            if (lower) {
                if (!state.tightenIntMin(variable, value, reason)) return false
            } else if (!state.tightenIntMax(variable, value, reason)) return false
        }
        return true
    }

    override fun explain(state: PropagationState, factorId: Int, payload: IntArray, atTrail: Int,
        atLevel: Int): IntArray? = compact?.let {
        explainLinearBound(state, it.coeffs, intVars, payload, atTrail, atLevel)
    } ?: explainRow(state, payload[0], atTrail)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray =
        explainRow(state, -1, state.undo.size)

    private fun compactRow(): LinearPropagator? {
        if (!row.bound.fitsLong() || row.coefficients.any { !it.fitsLong() }) return null
        val coefficients = LongArray(intVars.size)
        var remaining = Long.MAX_VALUE
        for (index in coefficients.indices) {
            val value = row.coefficients[index].toLongExact()
            if (value == Long.MIN_VALUE) return null
            val magnitude = if (value < 0L) -value else value
            if (magnitude > remaining) return null
            remaining -= magnitude
            coefficients[index] = value
        }
        return LinearPropagator(boolVars, intVars, coefficients, intVars, LinearOp.LE, row.bound.toLongExact())
    }

    private fun explainRow(state: PropagationState, excluded: Int, trail: Int): IntArray {
        val literals = ArrayList<Int>()
        if (row.guard != Lit.NONE) literals.add(row.guard xor 1)
        for (index in intVars.indices) {
            state.checkFiniteWork()
            if (index == excluded) continue
            val variable = intVars[index]
            val lower = row.coefficients[index] > BIG_ZERO
            val bound = state.boundAt(variable, lower, trail)
            val root = state.rootDomains[variable]
            if (if (lower) bound <= root.min else bound >= root.max) continue
            val atom = if (lower) state.atomVarGe(variable, bound) else state.atomVarLe(variable, bound)
            literals.add(Lit.make(atom, false))
        }
        return literals.toIntArray()
    }
}
