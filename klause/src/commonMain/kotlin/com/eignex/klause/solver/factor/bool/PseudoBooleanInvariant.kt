package com.eignex.klause.solver.factor.bool

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.factor.bool.internals.pbDegree
import com.eignex.klause.solver.factor.bool.internals.pbDistance
import com.eignex.klause.solver.factor.bool.internals.pbHolds
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.IntArrayList

/** LS contract for [PseudoBoolean]: violation scoring and break/make maintenance. */
interface PseudoBooleanInvariant : Invariant {

    /** Weights, parallel to [literals]. */
    val weights: IntArray

    /** Boolean literals contributing their weight when true. */
    val literals: IntArray

    /** Relation between the weighted sum and [bound]. */
    val op: PbOp

    /** Right-hand-side bound. */
    val bound: Int

    /** Signed contribution of [v] to the weighted sum. */
    fun signedForVar(v: Int): Int

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean =
        !pbHolds(state.longPayload[factorId], op, bound)

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        pbDegree(state.longPayload[factorId], op, bound, state.violationSoftCap)

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val signed = signedForVar(boolVar)
        val pre = state.assignment.boolValue(boolVar)
        val change = if (pre) -signed else signed
        val sum = state.longPayload[factorId]
        return pbDegree(
            sum + change,
            op,
            bound,
            state.violationSoftCap,
        ) - pbDegree(sum, op, bound, state.violationSoftCap)
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val signed = signedForVar(boolVar)
        val nowTrue = state.assignment.boolValue(boolVar)
        val change = if (nowTrue) signed else -signed
        val oldSum = state.longPayload[factorId]
        val newSum = oldSum + change
        state.longPayload[factorId] = newSum
        return pbDegree(newSum, op, bound, state.violationSoftCap) - pbDegree(oldSum, op, bound, state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val sum = state.longPayload[factorId]
        if (pbHolds(sum, op, bound)) return
        val curDist = pbDistance(sum, op, bound)
        for (i in literals.indices) {
            val lit = literals[i]
            val v = Lit.variable(lit)
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val change = if (isTrue) -weights[i] else weights[i]
            if (pbDistance(sum + change, op, bound) <= curDist) sink.addBoolFlip(v)
        }
    }

    /** Self-preserving moves during objective descent. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (literals.size < 2) return
        val sum = state.longPayload[factorId]
        val trueByWeight = HashMap<Int, IntArrayList>()
        val falseByWeight = HashMap<Int, IntArrayList>()
        for (i in literals.indices) {
            val lit = literals[i]
            val v = Lit.variable(lit)
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val effW = if (Lit.isPositive(lit)) weights[i] else -weights[i]
            val bucket = if (isTrue) trueByWeight else falseByWeight
            bucket.getOrPut(effW) { IntArrayList() }.add(v)
        }
        var proposed = 0
        outer@ for ((w, trueVars) in trueByWeight) {
            val falseVars = falseByWeight[w] ?: continue
            for (i in 0 until trueVars.size) {
                for (j in 0 until falseVars.size) {
                    if (trueVars[i] == falseVars[j]) continue
                    sink.addCompound(
                        listOf(
                            BoolFlip(trueVars[i]),
                            BoolFlip(falseVars[j]),
                        ),
                    )
                    proposed++
                    if (proposed >= PAIR_PROPOSAL_CAP) break@outer
                }
            }
        }
        val slack = when (op) {
            PbOp.LE -> bound - sum
            PbOp.GE -> sum - bound
            PbOp.EQ -> 0L
        }
        if (slack > 0L) {
            for (i in literals.indices) {
                val lit = literals[i]
                val v = Lit.variable(lit)
                val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
                val change = if (isTrue) -weights[i] else weights[i]
                val effChange = if (Lit.isPositive(lit)) change else -change
                val newSum = sum + effChange
                if (op == PbOp.LE && newSum <= bound) {
                    sink.addBoolFlip(v)
                } else if (op == PbOp.GE && newSum >= bound) {
                    sink.addBoolFlip(v)
                }
            }
        }
    }

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        val signedFlipped = signedForVar(flippedVar)
        if (signedFlipped == 0) return
        val newSum = state.longPayload[factorId]
        val flippedPost = state.assignment.boolValue(flippedVar)
        val changeV = if (flippedPost) signedFlipped else -signedFlipped
        val oldSum = newSum - changeV
        for (u in boolVars) {
            val signedU = signedForVar(u)
            if (signedU == 0) continue
            val uPost = state.assignment.boolValue(u)
            val uPre = if (u == flippedVar) !uPost else uPost
            val oldChangeU = if (uPre) -signedU else signedU
            val newChangeU = if (uPost) -signedU else signedU
            val preDelta = pbDegree(oldSum + oldChangeU, op, bound, state.violationSoftCap) -
                pbDegree(oldSum, op, bound, state.violationSoftCap)
            val postDelta = pbDegree(newSum + newChangeU, op, bound, state.violationSoftCap) -
                pbDegree(newSum, op, bound, state.violationSoftCap)
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

    /** Constants shared across [PseudoBooleanInvariant] implementations. */
    companion object {
        private const val PAIR_PROPOSAL_CAP: Int = 32
    }
}
