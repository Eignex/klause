package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.ExactBooleanSum
import com.eignex.klause.factor.bool.internals.pbFalseFormAntecedents
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.compareTo
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus

internal class ExactPseudoBooleanPropagator(
    constants: IntegralConstants,
    private val literals: IntArray,
    private val op: PbOp,
    private val auxBoolVar: Int = -1,
) : Propagator {
    private val body = ExactBooleanSum(constants, literals)

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        var lo = BIG_ZERO
        var hi = BIG_ZERO
        for (k in body.vars.indices) {
            val coefficient = body.coeffs[k]
            when (state.boolValues[body.vars[k]]) {
                true -> {
                    lo += coefficient
                    hi += coefficient
                }
                false -> Unit
                null -> {
                    if (coefficient < BIG_ZERO) lo += coefficient else hi += coefficient
                }
            }
        }
        if (auxBoolVar >= 0 && state.boolValues[auxBoolVar] == null) {
            val positive = possible(lo, hi, true)
            val negative = possible(lo, hi, false)
            if (!positive || !negative) {
                val reason = pbFalseFormAntecedents(state, literals, auxBoolVar, Lit.NONE)
                return state.pinBool(auxBoolVar, positive, reason)
            }
            return true
        }
        val wanted = if (auxBoolVar < 0) true else state.boolValues[auxBoolVar] ?: return true
        if (!possible(lo, hi, wanted)) return false
        val auxReason = if (auxBoolVar < 0) Lit.NONE else Lit.make(auxBoolVar, !wanted)
        for (k in body.vars.indices) {
            val variable = body.vars[k]
            if (state.boolValues[variable] != null) continue
            val coefficient = body.coeffs[k]
            val withoutLo = lo - if (coefficient < BIG_ZERO) coefficient else BIG_ZERO
            val withoutHi = hi - if (coefficient > BIG_ZERO) coefficient else BIG_ZERO
            val falseOk = possible(withoutLo, withoutHi, wanted)
            val trueOk = possible(withoutLo + coefficient, withoutHi + coefficient, wanted)
            if (!falseOk && !trueOk) return false
            if (falseOk == trueOk) continue
            val reason = pbFalseFormAntecedents(state, literals, variable, auxReason)
            if (!state.pinBool(variable, trueOk, reason)) return false
        }
        return true
    }

    private fun possible(lo: BigInt, hi: BigInt, truth: Boolean): Boolean = if (truth) {
        when (op) {
            PbOp.LE -> lo <= body.bound
            PbOp.GE -> hi >= body.bound
            PbOp.EQ -> lo <= body.bound && hi >= body.bound
        }
    } else {
        when (op) {
            PbOp.LE -> hi > body.bound
            PbOp.GE -> lo < body.bound
            PbOp.EQ -> lo != body.bound || hi != body.bound
        }
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val auxReason = if (auxBoolVar < 0) Lit.NONE else
            state.boolValues[auxBoolVar]?.let { Lit.make(auxBoolVar, !it) } ?: Lit.NONE
        return pbFalseFormAntecedents(state, literals, -1, auxReason)
    }
}
