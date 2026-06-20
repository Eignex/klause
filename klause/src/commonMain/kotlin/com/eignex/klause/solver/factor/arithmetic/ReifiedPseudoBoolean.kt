package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.factor.ReifiedFactor
import com.eignex.klause.solver.factor.bool.PseudoBooleanSumFactor
import com.eignex.klause.solver.factor.bool.pbDistance
import com.eignex.klause.solver.factor.bool.pbFalseFormAntecedents
import com.eignex.klause.solver.factor.bool.pbSumRange
import com.eignex.klause.solver.factor.bool.propagatePbBounds
import com.eignex.klause.solver.factor.bool.reifiedBoolApply
import com.eignex.klause.solver.factor.bool.reifiedBoolDelta
import com.eignex.klause.solver.factor.bool.reifiedBoolUpdateBreakMake
import com.eignex.klause.solver.factor.bool.reifiedDegree
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits
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

    /** `PseudoBoolean.structuralKey` plus the reifying [auxBoolVar]; the `rpb` prefix keeps it disjoint
     *  from a bare pseudo-Boolean's key (#443). */
    override fun structuralKey(): String = "rpb:$auxBoolVar:$op:$bound:" +
        literals.indices.sortedBy { literals[it] }.joinToString(",") { "${literals[it]}=${weights[it]}" }

    override val boolVars: IntArray = literals.litVars(auxBoolVar)

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean = holds(state.longPayload[factorId])

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        residual(state.longPayload[factorId], softCap)

    private fun degreeFor(sum: Long, aux: Boolean, softCap: Int): Int =
        reifiedDegree(aux, holds(sum)) { residual(sum, softCap) }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        reifiedBoolDelta(state, factorId, boolVar, auxBoolVar, signedByVar, ::degreeFor)

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        reifiedBoolApply(state, factorId, boolVar, auxBoolVar, signedByVar, ::degreeFor)

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

    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) =
        reifiedBoolUpdateBreakMake(state, factorId, flippedVar, auxBoolVar, signedByVar, boolVars, ::degreeFor)
}
