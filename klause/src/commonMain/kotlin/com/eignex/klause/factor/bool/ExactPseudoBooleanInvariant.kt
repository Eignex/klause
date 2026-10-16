package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.ExactBooleanSum
import com.eignex.klause.factor.bool.internals.reifiedDegree
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.IntIntMap
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus

internal class ExactPseudoBooleanInvariant(
    constants: IntegralConstants,
    literals: IntArray,
    private val op: PbOp,
    private val auxBoolVar: Int = -1,
) : Invariant {
    private val body = ExactBooleanSum(constants, literals)
    private val index = IntIntMap.build(body.vars, IntArray(body.vars.size) { it }, absent = -1)

    override fun initialize(state: LocalSearchState, factorId: Int) {
        state.refPayload[factorId] = body.sum(state.assignment::boolValue)
    }

    private fun sum(state: LocalSearchState, factorId: Int): BigInt = state.refPayload[factorId] as BigInt

    private fun holds(sum: BigInt): Boolean = body.holds(sum, op)

    private fun bodyDegree(sum: BigInt, softCap: Int): Int = body.degree(sum, op, softCap)

    private fun aux(state: LocalSearchState): Boolean = auxBoolVar < 0 || state.assignment.boolValue(auxBoolVar)

    private fun degree(sum: BigInt, aux: Boolean, softCap: Int): Int = if (auxBoolVar < 0) {
        bodyDegree(sum, softCap)
    } else {
        reifiedDegree(aux, holds(sum)) { bodyDegree(sum, softCap) }
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        if (auxBoolVar < 0) !holds(sum(state, factorId)) else aux(state) != holds(sum(state, factorId))

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        degree(sum(state, factorId), aux(state), state.violationSoftCap)

    private fun changed(sum: BigInt, boolVar: Int, before: Boolean): BigInt {
        val k = index[boolVar]
        if (k < 0) return sum
        return if (before) sum - body.coeffs[k] else sum + body.coeffs[k]
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val sum = sum(state, factorId)
        val a = aux(state)
        val nextAux = if (boolVar == auxBoolVar) !a else a
        val nextSum = changed(sum, boolVar, state.assignment.boolValue(boolVar))
        return degree(nextSum, nextAux, state.violationSoftCap) - degree(sum, a, state.violationSoftCap)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val oldSum = sum(state, factorId)
        val a = aux(state)
        val oldAux = if (boolVar == auxBoolVar) !a else a
        val nextSum = changed(oldSum, boolVar, !state.assignment.boolValue(boolVar))
        state.refPayload[factorId] = nextSum
        return degree(nextSum, a, state.violationSoftCap) - degree(oldSum, oldAux, state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (!isViolated(state, factorId)) return
        for (variable in body.vars) {
            if (deltaIfBoolFlipped(state, factorId, variable) <= 0) sink.addChannelingBoolFlip(state, variable)
        }
        if (auxBoolVar >= 0) sink.addChannelingBoolFlip(state, auxBoolVar)
    }
}
