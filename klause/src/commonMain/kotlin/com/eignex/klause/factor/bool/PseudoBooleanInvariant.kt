package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.buildSignedWeightByVar
import com.eignex.klause.factor.bool.internals.nonReifiedBoolUpdateBreakMakeLoop
import com.eignex.klause.factor.bool.internals.pbDegree
import com.eignex.klause.factor.bool.internals.pbDistance
import com.eignex.klause.factor.bool.internals.pbHolds
import com.eignex.klause.factor.bool.internals.signedFlipDelta
import com.eignex.klause.ir.Lit
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.BoolFlip
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntLongMap
import com.eignex.klause.util.MutableLongObjectMap

internal class PseudoBooleanInvariant(
    private val boolVars: IntArray,
    private val weights: LongArray,
    private val literals: IntArray,
    private val op: PbOp,
    private val bound: Long,
) : Invariant {

    /** Signed contribution of each variable to the weighted sum. */
    private val signedByVar: IntLongMap = buildSignedWeightByVar(weights, literals, exclude = -1)

    private fun signedForVar(v: Int): Long = signedByVar[v]

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
        for (v in boolVars) {
            val change = signedFlipDelta(state, signedByVar, v, current = true)
            if (pbDistance(sum + change, op, bound) <= curDist) sink.addChannelingBoolFlip(state, v)
        }
    }

    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (literals.size < 2) return
        val sum = state.longPayload[factorId]
        val decreasingByWeight = MutableLongObjectMap<IntArrayList>()
        val increasingByWeight = MutableLongObjectMap<IntArrayList>()
        for (v in boolVars) {
            val change = signedFlipDelta(state, signedByVar, v, current = true)
            val decreasing = change < 0L || (change == 0L && state.assignment.boolValue(v))
            val bucket = if (decreasing) decreasingByWeight else increasingByWeight
            // The word kernel's activity-width check bounds every aggregate flip magnitude.
            val weight = if (change < 0L) -change else change
            bucket.getOrPut(weight) { IntArrayList() }.add(v)
        }
        var proposed = 0
        decreasingByWeight.forEach { w, decreasingVars ->
            if (proposed < PAIR_PROPOSAL_CAP) {
                val increasingVars = increasingByWeight[w]
                if (increasingVars != null) {
                    for (i in 0 until decreasingVars.size) {
                        for (j in 0 until increasingVars.size) {
                            sink.addCompound(
                                listOf(
                                    BoolFlip(decreasingVars[i]),
                                    BoolFlip(increasingVars[j]),
                                ),
                            )
                            proposed++
                            if (proposed >= PAIR_PROPOSAL_CAP) return@forEach
                        }
                    }
                }
            }
        }
        val slack = when (op) {
            PbOp.LE -> bound - sum
            PbOp.GE -> sum - bound
            PbOp.EQ -> 0L
        }
        if (slack > 0L) {
            for (v in boolVars) {
                val change = signedFlipDelta(state, signedByVar, v, current = true)
                val newSum = sum + change
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
        if (signedFlipped == 0L) return
        val newSum = state.longPayload[factorId]
        val flippedPost = state.assignment.boolValue(flippedVar)
        val changeV = if (flippedPost) signedFlipped else -signedFlipped
        val oldSum = newSum - changeV
        nonReifiedBoolUpdateBreakMakeLoop(state, flippedVar, signedByVar, boolVars, oldSum, newSum) { sum, cap ->
            pbDegree(sum, op, bound, cap)
        }
    }

    companion object {
        private const val PAIR_PROPOSAL_CAP: Int = 32
    }
}
