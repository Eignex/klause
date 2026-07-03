package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.bool.internals.pbFalseFormAntecedents
import com.eignex.klause.factor.bool.internals.pbSumRange
import com.eignex.klause.factor.bool.internals.propagatePbBounds
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Lit

/** CP propagator for [ReifiedPseudoBoolean]: reified pseudo-Boolean propagation. */
internal class ReifiedPseudoBooleanPropagator(
    private val auxBoolVar: Int,
    private val weights: IntArray,
    private val literals: IntArray,
    private val op: PbOp,
    private val bound: Int,
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
        val bnd = bound.toLong()
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
        if (alwaysHolds) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = auxBoolVar, extraLit = 0)
            return state.pinBool(auxBoolVar, true, ant)
        }
        if (neverHolds) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = auxBoolVar, extraLit = 0)
            return state.pinBool(auxBoolVar, false, ant)
        }

        val aux = state.boolValues[auxBoolVar] ?: return true
        val auxAntecedent = Lit.make(auxBoolVar, !aux)
        return if (aux) {
            propagatePbBounds(state, weights, literals, op, bnd, extraLit = auxAntecedent)
        } else {
            when (op) {
                PbOp.LE -> propagatePbBounds(state, weights, literals, PbOp.GE, bnd + 1, extraLit = auxAntecedent)
                PbOp.GE -> propagatePbBounds(state, weights, literals, PbOp.LE, bnd - 1, extraLit = auxAntecedent)
                PbOp.EQ -> propagatePbNotEqual(state, weights, literals, bnd, extraLit = auxAntecedent)
            }
        }
    }

    private fun propagatePbNotEqual(
        state: PropagationState,
        weights: IntArray,
        literals: IntArray,
        bound: Long,
        extraLit: Int = 0,
    ): Boolean {
        val n = literals.size
        val litLo = LongArray(n)
        val litHi = LongArray(n)
        var sumLo = 0L
        var sumHi = 0L
        for (i in 0 until n) {
            val w = weights[i].toLong()
            val v = Lit.variable(literals[i])
            val b = state.boolValues[v]
            val lo: Long
            val hi: Long
            when {
                b == null -> {
                    lo = minOf(0L, w)
                    hi = maxOf(0L, w)
                }

                Lit.evaluate(literals[i], b) -> {
                    lo = w
                    hi = w
                }

                else -> {
                    lo = 0L
                    hi = 0L
                }
            }
            litLo[i] = lo
            litHi[i] = hi
            sumLo += lo
            sumHi += hi
        }
        if (sumLo == bound && sumHi == bound) return false
        for (i in 0 until n) {
            val w = weights[i].toLong()
            if (w == 0L) continue
            val v = Lit.variable(literals[i])
            if (state.boolValues[v] != null) continue
            val otherLo = sumLo - litLo[i]
            val otherHi = sumHi - litHi[i]
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
