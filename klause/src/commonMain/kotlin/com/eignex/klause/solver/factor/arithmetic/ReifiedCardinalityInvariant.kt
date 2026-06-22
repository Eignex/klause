package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.factor.bool.internals.reifiedDegree
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.IntIntMap

/**
 * LS invariant for [ReifiedCardinality]: reified cardinality violation tracking and repair.
 */
internal class ReifiedCardinalityInvariant(
    private val auxBoolVar: Int,
    private val literals: IntArray,
    private val min: Int,
    private val max: Int,
    override val boolVars: IntArray,
    override val intVars: IntArray,
) : Invariant {

    private val signedByVar: IntIntMap

    init {
        val signs = HashMap<Int, Int>()
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == auxBoolVar) continue
            signs[v] = (signs[v] ?: 0) + if (Lit.isPositive(lit)) 1 else -1
        }
        signedByVar = IntIntMap.build(keys = signs.keys.toIntArray(), values = signs.values.toIntArray(), absent = 0)
    }

    private fun reifSignedFor(v: Int): Int = signedByVar[v]

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
            if (signed == 0) return 0
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
        if (signed == 0) return 0
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
        val newTotal = state.longPayload[factorId]
        val newAux = state.assignment.boolValue(auxBoolVar)
        val oldAux: Boolean
        val oldTotal: Long
        if (flippedVar == auxBoolVar) {
            oldAux = !newAux
            oldTotal = newTotal
        } else {
            oldAux = newAux
            val signedFlipped = reifSignedFor(flippedVar)
            if (signedFlipped == 0) return
            val flippedPost = state.assignment.boolValue(flippedVar)
            val changeV = if (flippedPost) signedFlipped else -signedFlipped
            oldTotal = newTotal - changeV
        }
        val cap = state.violationSoftCap
        val oldDeg = reifDegree(oldTotal, oldAux, cap)
        val newDeg = reifDegree(newTotal, newAux, cap)
        for (u in boolVars) {
            val preDelta: Int
            val postDelta: Int
            if (u == auxBoolVar) {
                preDelta = reifDegree(oldTotal, !oldAux, cap) - oldDeg
                postDelta = reifDegree(newTotal, !newAux, cap) - newDeg
            } else {
                val signedU = reifSignedFor(u)
                if (signedU == 0) {
                    preDelta = 0
                    postDelta = 0
                } else {
                    val uPost = state.assignment.boolValue(u)
                    val uPre = if (u == flippedVar) !uPost else uPost
                    val preChangeU = if (uPre) -signedU else signedU
                    val postChangeU = if (uPost) -signedU else signedU
                    preDelta = reifDegree(oldTotal + preChangeU, oldAux, cap) - oldDeg
                    postDelta = reifDegree(newTotal + postChangeU, newAux, cap) - newDeg
                }
            }
            val preBreak = preDelta > 0
            val preMake = preDelta < 0
            val postBreak = postDelta > 0
            val postMake = postDelta < 0
            if (preBreak != postBreak) {
                if (postBreak) state.boolBreakCount[u]++ else state.boolBreakCount[u]--
            }
            if (preMake != postMake) {
                if (postMake) state.boolMakeCount[u]++ else state.boolMakeCount[u]--
            }
        }
    }

    private fun reifHolds(n: Long): Boolean = n >= min && n <= max

    private fun reifDistance(n: Long): Long = (if (n < min) min - n else 0L) + (if (n > max) n - max else 0L)

    private fun reifDegree(n: Long, aux: Boolean, softCap: Int): Int =
        reifiedDegree(aux, reifHolds(n)) { compressViolation(reifDistance(n), softCap) }
}
