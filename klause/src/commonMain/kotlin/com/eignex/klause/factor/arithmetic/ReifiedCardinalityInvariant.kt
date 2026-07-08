package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.bool.internals.buildSignedLitsByVar
import com.eignex.klause.factor.bool.internals.reifiedBoolUpdateBreakMake
import com.eignex.klause.factor.bool.internals.reifiedDegree
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.BoolFlip
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntLongMap

/**
 * LS invariant for [ReifiedCardinality]: reified cardinality violation tracking and repair.
 */
internal class ReifiedCardinalityInvariant(
    private val auxBoolVar: Int,
    private val literals: IntArray,
    private val min: Int,
    private val max: Int,
    private val boolVars: IntArray,
) : Invariant {

    private val signedByVar: IntLongMap = buildSignedLitsByVar(literals, exclude = auxBoolVar)

    private fun reifSignedFor(v: Int): Long = signedByVar[v]

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        state.assignment.boolValue(auxBoolVar) != reifHolds(state.longPayload[factorId])

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val n = state.longPayload[factorId]
        return when {
            aux == reifHolds(n) -> 0
            aux -> compressViolation(reifDistance(n), state.violationSoftCap)
            else -> 1
        }
    }

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var count = 0L
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) count++
        }
        state.longPayload[factorId] = count
    }

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
        val n = state.longPayload[factorId]
        if (aux == reifHolds(n)) return
        sink.addBoolFlip(auxBoolVar)
        val auxFlip = BoolFlip(auxBoolVar)
        val wantInRange = aux
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == auxBoolVar) continue
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val newN = n + if (isTrue) -1L else 1L
            if (wantInRange == reifHolds(newN)) sink.addBoolFlip(v)
            if (wantInRange != reifHolds(newN)) sink.addCompound(listOf(auxFlip, BoolFlip(v)))
        }
    }

    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        reifiedBoolUpdateBreakMake(state, factorId, flippedVar, auxBoolVar, signedByVar, boolVars) { total, aux, cap ->
            reifDegree(total, aux, cap)
        }
    }

    private fun reifHolds(n: Long): Boolean = n >= min && n <= max

    private fun reifDistance(n: Long): Long = (if (n < min) min - n else 0L) + (if (n > max) n - max else 0L)

    private fun reifDegree(n: Long, aux: Boolean, softCap: Int): Int =
        reifiedDegree(aux, reifHolds(n)) { compressViolation(reifDistance(n), softCap) }
}
