package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.util.IntIntMap

/** LS invariant for [Cardinality]: violation scoring and break/make maintenance for `min ≤ count ≤ max`. */
internal class CardinalityInvariant(
    override val boolVars: IntArray,
    override val intVars: IntArray,
    private val literals: IntArray,
    private val min: Int,
    private val max: Int,
) : Invariant {

    /** Signed contribution of each variable to the count, built from [literals]. */
    private val signedByVar: IntIntMap = run {
        val signs = HashMap<Int, Int>()
        for (lit in literals) {
            val v = Lit.variable(lit)
            signs[v] = (signs[v] ?: 0) + if (Lit.isPositive(lit)) 1 else -1
        }
        IntIntMap.build(keys = signs.keys.toIntArray(), values = signs.values.toIntArray(), absent = 0)
    }

    private fun signedForVar(v: Int): Int = signedByVar[v]

    override fun initialize(state: LocalSearchState, factorId: Int) {
        var count = 0L
        for (lit in literals) {
            if (Lit.evaluate(lit, state.assignment.boolValue(Lit.variable(lit)))) count++
        }
        state.longPayload[factorId] = count
    }

    /** Cached max |`signedByVar[v]`| across `boolVars`. Bounds the change `n` can
     *  see from a single flip, used by [updateBoolBreakMakeForFlip]'s early-out. */
    private val maxAbsSigned: Int = run {
        var m = 0
        for (v in boolVars) {
            val s = signedByVar[v]
            val a = if (s < 0) -s else s
            if (a > m) m = a
        }
        m
    }

    private fun cardHolds(n: Long): Boolean = n >= min && n <= max

    private fun cardDegree(n: Long, softCap: Int): Int {
        val dist = (if (n < min) min - n else 0L) + (if (n > max) n - max else 0L)
        if (dist == 0L) return 0
        return compressViolation(dist, softCap)
    }

    override fun isViolated(state: LocalSearchState, factorId: Int): Boolean = !cardHolds(state.longPayload[factorId])

    override fun violationDegree(state: LocalSearchState, factorId: Int): Int =
        cardDegree(state.longPayload[factorId], state.violationSoftCap)

    /** Compressed Δ violation-degree if `u` (currently `uVal`) were flipped at true-count `n`. */
    private fun signedDelta(n: Long, u: Int, uVal: Boolean, softCap: Int): Int {
        val signedU = signedForVar(u)
        if (signedU == 0) return 0
        val changeU = if (uVal) -signedU else signedU
        return cardDegree(n + changeU, softCap) - cardDegree(n, softCap)
    }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        signedDelta(state.longPayload[factorId], boolVar, state.assignment.boolValue(boolVar), state.violationSoftCap)

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val signed = signedForVar(boolVar)
        val nowTrue = state.assignment.boolValue(boolVar)
        val change = if (nowTrue) signed else -signed
        val oldN = state.longPayload[factorId]
        val newN = oldN + change
        state.longPayload[factorId] = newN
        return cardDegree(newN, state.violationSoftCap) - cardDegree(oldN, state.violationSoftCap)
    }

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val n = state.longPayload[factorId]
        if (cardHolds(n)) return
        val wantIncrease = n < min
        if (boolVars.size == literals.size) {
            for (lit in literals) {
                val v = Lit.variable(lit)
                val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
                val helpsIncrease = !isTrue
                if (wantIncrease == helpsIncrease) sink.addBoolFlip(v)
            }
            return
        }
        for (v in boolVars) {
            var netChange = 0
            for (lit in literals) {
                if (Lit.variable(lit) != v) continue
                netChange += if (Lit.evaluate(lit, state.assignment.boolValue(v))) -1 else +1
            }
            if (wantIncrease && netChange > 0) {
                sink.addBoolFlip(v)
            } else if (!wantIncrease && netChange < 0) {
                sink.addBoolFlip(v)
            }
        }
    }

    /** Self-preserving moves during objective descent: swap one currently-true literal with
     *  one currently-false literal to preserve count `n`. */
    override fun proposeStructuredMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        if (boolVars.size < 2 || literals.size < 2) return
        if (boolVars.size != literals.size) return
        val trueLits = IntArray(literals.size)
        val falseLits = IntArray(literals.size)
        var nT = 0
        var nF = 0
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (Lit.evaluate(lit, state.assignment.boolValue(v))) {
                trueLits[nT++] = v
            } else {
                falseLits[nF++] = v
            }
        }
        if (nT == 0 || nF == 0) return
        val total = nT * nF
        if (total <= PAIR_PROPOSAL_CAP) {
            for (i in 0 until nT) {
                for (j in 0 until nF) {
                    sink.addCompound(
                        listOf(
                            BoolFlip(trueLits[i]),
                            BoolFlip(falseLits[j]),
                        ),
                    )
                }
            }
        } else {
            val rng = state.rng
            repeat(PAIR_PROPOSAL_CAP) {
                val a = trueLits[rng.nextInt(nT)]
                val b = falseLits[rng.nextInt(nF)]
                sink.addCompound(
                    listOf(
                        BoolFlip(a),
                        BoolFlip(b),
                    ),
                )
            }
        }
    }

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    /** Adjust break/make counts after [flippedVar] has been flipped. Fast-path early-out
     *  when both pre- and post-flip counts sit strictly inside the interior. */
    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        val signedFlipped = signedForVar(flippedVar)
        if (signedFlipped == 0) return
        val newN = state.longPayload[factorId]
        val flippedPost = state.assignment.boolValue(flippedVar)
        val changeV = if (flippedPost) signedFlipped else -signedFlipped
        val oldN = newN - changeV
        val oldViolated = oldN < min || oldN > max
        val newViolated = newN < min || newN > max
        if (!oldViolated && !newViolated &&
            oldN - maxAbsSigned >= min && oldN + maxAbsSigned <= max &&
            newN - maxAbsSigned >= min && newN + maxAbsSigned <= max
        ) {
            return
        }
        for (u in boolVars) {
            val signedU = signedForVar(u)
            if (signedU == 0) continue
            val uPost = state.assignment.boolValue(u)
            val uPre = if (u == flippedVar) !uPost else uPost
            val preDelta = signedDelta(oldN, u, uPre, state.violationSoftCap)
            val postDelta = signedDelta(newN, u, uPost, state.violationSoftCap)
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

    companion object {
        /** Cap on (true-lit, false-lit) swap-pair proposals in [proposeStructuredMoves]. */
        const val PAIR_PROPOSAL_CAP: Int = 32
    }
}
