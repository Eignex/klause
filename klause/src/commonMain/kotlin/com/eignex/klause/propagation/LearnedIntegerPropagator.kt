package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.arithmetic.internals.propagateLinearBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.EmptyIntArray

internal class LearnedIntegerPropagator(val row: IntegerConflictRow) : LearnedPropagator {
    override val boolVars: IntArray = if (row.guard == Lit.NONE) EmptyIntArray else intArrayOf(Lit.variable(row.guard))
    override val intVars: IntArray = row.variables
    private val linear = LinearPropagator(boolVars, intVars, row.coefficients, row.variables, LinearOp.LE, row.bound)
    override val initialIntEventWatches: IntArray = linear.initialIntEventWatches

    override fun propagate(state: PropagationState, factorId: Int): Boolean =
        !state.integerGuardTrueAt(row.guard) ||
        // Eager reasons retain their historical atoms without a compactable lazy factor id.
        propagateLinearBounds(state, row.coefficients, row.variables, LinearOp.LE, row.bound,
            extraLit = row.guard xor 1, includeExtraLit = row.guard != Lit.NONE, factorId = -1)

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        linear.conflictReason(state, factorId)?.let { reason ->
            if (row.guard == Lit.NONE) reason else reason + (row.guard xor 1)
        }
}
