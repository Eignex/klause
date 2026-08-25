package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.arithmetic.internals.reifiedAuxTail
import com.eignex.klause.factor.bool.internals.pbFalseFormAntecedents
import com.eignex.klause.factor.bool.internals.pbLitRanges
import com.eignex.klause.factor.bool.internals.pbSumRange
import com.eignex.klause.factor.bool.internals.propagatePbBounds
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator

/** CP propagator for [ReifiedPseudoBoolean]: reified pseudo-Boolean propagation. */
internal class ReifiedPseudoBooleanPropagator(
    private val auxBoolVar: Int,
    private val weights: LongArray,
    private val literals: IntArray,
    private val op: PbOp,
    private val bound: Long,
    val boolVars: IntArray,
    val intVars: IntArray,
) : Propagator {

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val auxLit = state.boolValues[auxBoolVar]?.let { Lit.make(auxBoolVar, !it) } ?: 0
        return pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = auxLit)
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        val range = pbSumRange(state, weights, literals)
        val sumLo = range[0]
        val sumHi = range[1]
        val bnd = bound
        val alwaysHolds = when (op) {
            PbOp.LE -> sumHi <= bnd
            PbOp.GE -> sumLo >= bnd
            PbOp.EQ -> sumLo == bnd && sumHi == bnd
        }
        val neverHolds = when (op) {
            PbOp.LE -> sumLo > bnd
            PbOp.GE -> sumHi < bnd
            PbOp.EQ -> sumLo > bnd || sumHi < bnd
        }
        return state.reifiedAuxTail(
            auxBoolVar,
            alwaysHolds,
            neverHolds,
            pinAntecedent = { pbFalseFormAntecedents(state, literals, excludeVar = auxBoolVar, extraLit = 0) },
            propagateTrue = { a -> propagatePbBounds(state, weights, literals, op, bnd, extraLit = a) },
            propagateFalse = { a ->
                when (val falseForm = falseForm(op, bnd)) {
                    null -> false

                    is FalseForm.Inequality ->
                        propagatePbBounds(state, weights, literals, falseForm.op, falseForm.bound, extraLit = a)

                    FalseForm.NotEqual -> propagatePbNotEqual(state, weights, literals, bnd, extraLit = a)
                }
            },
        )
    }

    private fun falseForm(op: PbOp, bound: Long): FalseForm? = when (op) {
        PbOp.LE -> if (bound == Long.MAX_VALUE) null else FalseForm.Inequality(PbOp.GE, bound + 1L)
        PbOp.GE -> if (bound == Long.MIN_VALUE) null else FalseForm.Inequality(PbOp.LE, bound - 1L)
        PbOp.EQ -> FalseForm.NotEqual
    }

    private sealed interface FalseForm {
        data class Inequality(val op: PbOp, val bound: Long) : FalseForm

        data object NotEqual : FalseForm
    }

    private fun propagatePbNotEqual(
        state: PropagationState,
        weights: LongArray,
        literals: IntArray,
        bound: Long,
        extraLit: Int = 0,
    ): Boolean {
        val r = pbLitRanges(state, weights, literals)
        val sumLo = r.sumLo
        val sumHi = r.sumHi
        if (sumLo == bound && sumHi == bound) return false
        for (i in literals.indices) {
            val w = weights[i]
            if (w == 0L) continue
            val v = Lit.variable(literals[i])
            if (state.boolValues[v] != null) continue
            val otherLo = sumLo - r.litLo[i]
            val otherHi = sumHi - r.litHi[i]
            val trueOk = !(otherLo + w == bound && otherHi + w == bound)
            val falseOk = !(otherLo == bound && otherHi == bound)
            if (!trueOk && !falseOk) return false
            if (!trueOk) {
                val ant = pbFalseFormAntecedents(state, literals, excludeVar = v, extraLit = extraLit)
                if (!state.pinBool(v, !Lit.isPositive(literals[i]), ant)) return false
            } else if (!falseOk) {
                val ant = pbFalseFormAntecedents(state, literals, excludeVar = v, extraLit = extraLit)
                if (!state.pinBool(v, Lit.isPositive(literals[i]), ant)) return false
            }
        }
        return true
    }
}
