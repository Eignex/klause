package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.factor.ReifiedFactor
import com.eignex.klause.solver.factor.bool.reifiedDegree
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * LS contract for [ReifiedCardinality]: reified cardinality violation tracking and repair.
 */
interface ReifiedCardinalityInvariant : ReifiedFactor {

    /** The reifying Boolean variable id. */
    override val auxBoolVar: Int

    /** The Boolean literals. */
    val literals: IntArray

    /** Inclusive lower bound. */
    val min: Int

    /** Inclusive upper bound (also used as `true` for max-mode in `ArrayMinMax`). */
    val max: Int

    /** Signed contribution of [v] to the true count. */
    fun reifSignedFor(v: Int): Int

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean =
        reifHolds(state.longPayload[factorId])

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        compressViolation(reifDistance(state.longPayload[factorId]), softCap)

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
