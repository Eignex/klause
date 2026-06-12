package com.eignex.klause.solver.factor

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.propagation.PropagationState

/**
 * `auxBoolVar ↔ (Σ weights(i) * lit(i) ⟨op⟩ bound)`. Payload at `intPayload(factorId)` is the
 * current weighted sum.
 */
class ReifiedPseudoBoolean(override val auxBoolVar: Int, weights: IntArray, literals: IntArray, op: PbOp, bound: Int) :
    PseudoBooleanSumFactor(weights, literals, op, bound, excludedVar = auxBoolVar),
    ReifiedFactor {

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        ReifiedPseudoBoolean(boolMap[auxBoolVar], weights, literals.remapLits(boolMap), op, bound)

    override val boolVars: IntArray = literals.litVars(auxBoolVar)

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean = holds(state.longPayload[factorId])

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        residual(state.longPayload[factorId], softCap)

    private fun degreeFor(sum: Long, aux: Boolean, softCap: Int): Int =
        reifiedDegree(aux, holds(sum)) { residual(sum, softCap) }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.longPayload[factorId]
        val cap = state.violationSoftCap
        return if (boolVar == auxBoolVar) {
            degreeFor(sum, !aux, cap) - degreeFor(sum, aux, cap)
        } else {
            val change = signedFlipDelta(state, signedByVar, boolVar, current = true)
            degreeFor(sum + change, aux, cap) - degreeFor(sum, aux, cap)
        }
    }

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int {
        val oldSum = state.longPayload[factorId]
        val cap = state.violationSoftCap
        if (boolVar == auxBoolVar) {
            val newAux = state.assignment.boolValue(auxBoolVar)
            return degreeFor(oldSum, newAux, cap) - degreeFor(oldSum, !newAux, cap)
        }
        val change = signedFlipDelta(state, signedByVar, boolVar, current = false)
        val newSum = oldSum + change
        state.longPayload[factorId] = newSum
        val aux = state.assignment.boolValue(auxBoolVar)
        return degreeFor(newSum, aux, cap) - degreeFor(oldSum, aux, cap)
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
        // For aux pins: the precondition is the current pinning of the constraint
        // literals — pbFalseFormAntecedents collects them as currently-false lits.
        if (alwaysHolds) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = auxBoolVar, extraLit = 0)
            return state.pinBool(auxBoolVar, true, ant)
        }
        if (neverHolds) {
            val ant = pbFalseFormAntecedents(state, literals, excludeVar = auxBoolVar, extraLit = 0)
            return state.pinBool(auxBoolVar, false, ant)
        }

        val aux = state.boolValues[auxBoolVar] ?: return true
        // Aux is pinned — its current pinning is the context that selects which side of
        // the reification we're propagating. Pass it as `extraLit` so downstream pins
        // record it as an antecedent (the aux pin's false-form is `Lit.make(aux, !aux)`).
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

    /** Clause-form nogood: the disjunction of every pinned literal's false-form. Includes
     *  the aux var when it's pinned (its current pinning selected the propagation path
     *  that failed). */
    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? {
        val auxLit = state.boolValues[auxBoolVar]?.let { Lit.make(auxBoolVar, !it) } ?: 0
        return pbFalseFormAntecedents(state, literals, excludeVar = -1, extraLit = auxLit)
    }

    /**
     * Propagate `Σ weights`i` · lit_i ≠ bound`. Returns `false` iff the constraint is
     * infeasible — i.e. the sum is forced to be exactly [bound] regardless of remaining
     * free literals. Otherwise prunes any single literal whose two polarities would both
     * collapse the sum to [bound] (rare; usually nothing to prune).
     */
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
            // Polarity "literal true" contributes `w` to the sum.
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

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val aux = state.assignment.boolValue(auxBoolVar)
        val sum = state.longPayload[factorId]
        if (aux == holds(sum)) return
        sink.addBoolFlip(auxBoolVar)
        val auxFlip = BoolFlip(auxBoolVar)
        val wantHolds = aux
        val curDist = distanceToInRange(sum)
        for (i in literals.indices) {
            val lit = literals[i]
            val v = Lit.variable(lit)
            if (v == auxBoolVar) continue
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val change = if (isTrue) -weights[i] else weights[i]
            val newDist = distanceToInRange(sum + change)
            val improvesSame = if (wantHolds) newDist <= curDist else newDist >= curDist
            if (improvesSame) sink.addBoolFlip(v)
            // Toggle-driven sub-region exploration: pair aux flip with a body flip whose
            // shift drives sum toward the *opposite* satisfying region. Lets strategies
            // escape the current reification side atomically.
            val improvesOpp = if (wantHolds) newDist >= curDist else newDist <= curDist
            if (improvesOpp && !improvesSame) {
                sink.addCompound(listOf(auxFlip, BoolFlip(v)))
            }
        }
    }

    private fun distanceToInRange(sum: Long): Long = pbDistance(sum, op, bound)

    override val maintainsBreakMakeIncrementally: Boolean get() = true

    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) {
        val newSum = state.longPayload[factorId]
        val newAux = state.assignment.boolValue(auxBoolVar)
        val oldAux: Boolean
        val oldSum: Long
        if (flippedVar == auxBoolVar) {
            oldAux = !newAux
            oldSum = newSum
        } else {
            oldAux = newAux
            val signedFlipped = signedByVar[flippedVar]
            if (signedFlipped == 0) return
            val flippedPost = state.assignment.boolValue(flippedVar)
            val changeV = if (flippedPost) signedFlipped else -signedFlipped
            oldSum = newSum - changeV
        }
        val cap = state.violationSoftCap
        val oldDeg = degreeFor(oldSum, oldAux, cap)
        val newDeg = degreeFor(newSum, newAux, cap)
        for (u in boolVars) {
            // Graded Δ each var's flip would produce (the value deltaIfBoolFlipped returns),
            // evaluated against the pre- and post-flip (sum, aux) — break/make track its sign.
            val preDelta: Int
            val postDelta: Int
            if (u == auxBoolVar) {
                preDelta = degreeFor(oldSum, !oldAux, cap) - oldDeg
                postDelta = degreeFor(newSum, !newAux, cap) - newDeg
            } else {
                val signedU = signedByVar[u]
                if (signedU == 0) {
                    preDelta = 0
                    postDelta = 0
                } else {
                    val uPost = state.assignment.boolValue(u)
                    val uPre = if (u == flippedVar) !uPost else uPost
                    val preChangeU = if (uPre) -signedU else signedU
                    val postChangeU = if (uPost) -signedU else signedU
                    preDelta = degreeFor(oldSum + preChangeU, oldAux, cap) - oldDeg
                    postDelta = degreeFor(newSum + postChangeU, newAux, cap) - newDeg
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
}
