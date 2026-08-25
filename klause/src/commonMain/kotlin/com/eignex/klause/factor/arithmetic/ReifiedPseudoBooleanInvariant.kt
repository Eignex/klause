package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.bool.internals.buildSignedWeightByVar
import com.eignex.klause.factor.bool.internals.pbDistance
import com.eignex.klause.factor.bool.internals.pbHolds
import com.eignex.klause.factor.bool.internals.reifiedBoolUpdateBreakMake
import com.eignex.klause.factor.bool.internals.reifiedDegree
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.BoolFlip
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.IntLongMap

/**
 * LS invariant for [ReifiedPseudoBoolean]: reified pseudo-Boolean violation tracking and repair.
 */
internal class ReifiedPseudoBooleanInvariant(
    private val auxBoolVar: Int,
    private val weights: LongArray,
    private val literals: IntArray,
    private val op: PbOp,
    private val bound: Long,
    private val boolVars: IntArray,
) : Invariant {

    private val signedByVar: IntLongMap = buildSignedWeightByVar(weights, literals, exclude = auxBoolVar)

    private fun reifSignedFor(v: Int): Long = signedByVar[v]

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var sum = 0L
        for (i in literals.indices) {
            if (Lit.evaluate(literals[i], state.assignment.boolValue(Lit.variable(literals[i])))) {
                sum += weights[i]
            }
        }
        state.longPayload[factorId] = sum
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        state.assignment.boolValue(auxBoolVar) != pbHolds(state.longPayload[factorId], op, bound)

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.longPayload[factorId]
        return when {
            aux == pbHolds(sum, op, bound) -> 0
            aux -> compressViolation(pbDistance(sum, op, bound), state.violationSoftCap)
            else -> 1
        }
    }

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val total = state.longPayload[factorId]
        val cap = state.violationSoftCap
        return if (boolVar == auxBoolVar) {
            reifDegree(total, !aux, cap) - reifDegree(total, aux, cap)
        } else {
            val signed = reifSignedFor(boolVar)
            if (signed == 0L) return 0
            val pre = state.assignment.boolValue(boolVar)
            val change = if (pre) -signed else signed
            reifDegree(total + change, aux, cap) - reifDegree(total, aux, cap)
        }
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val oldTotal = state.longPayload[factorId]
        val cap = state.violationSoftCap
        if (boolVar == auxBoolVar) {
            val newAux = state.assignment.boolValue(auxBoolVar)
            return reifDegree(oldTotal, newAux, cap) - reifDegree(oldTotal, !newAux, cap)
        }
        val signed = reifSignedFor(boolVar)
        if (signed == 0L) return 0
        val pre = !state.assignment.boolValue(boolVar)
        val change = if (pre) -signed else signed
        val newTotal = oldTotal + change
        state.longPayload[factorId] = newTotal
        val aux = state.assignment.boolValue(auxBoolVar)
        return reifDegree(newTotal, aux, cap) - reifDegree(oldTotal, aux, cap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.longPayload[factorId]
        if (aux == pbHolds(sum, op, bound)) return
        sink.addBoolFlip(auxBoolVar)
        val auxFlip = BoolFlip(auxBoolVar)
        val wantHolds = aux
        val curDist = pbDistance(sum, op, bound)
        for (i in literals.indices) {
            val lit = literals[i]
            val v = Lit.variable(lit)
            if (v == auxBoolVar) continue
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val change = if (isTrue) -weights[i] else weights[i]
            val newDist = pbDistance(sum + change, op, bound)
            val improvesSame = if (wantHolds) newDist <= curDist else newDist >= curDist
            if (improvesSame) sink.addBoolFlip(v)
            val improvesOpp = if (wantHolds) newDist >= curDist else newDist <= curDist
            if (improvesOpp && !improvesSame) sink.addCompound(listOf(auxFlip, BoolFlip(v)))
        }
    }

    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        reifiedBoolUpdateBreakMake(state, factorId, flippedVar, auxBoolVar, signedByVar, boolVars) { total, aux, cap ->
            reifDegree(total, aux, cap)
        }
    }

    private fun reifDegree(sum: Long, aux: Boolean, softCap: Int): Int =
        reifiedDegree(aux, pbHolds(sum, op, bound)) { compressViolation(pbDistance(sum, op, bound), softCap) }
}
