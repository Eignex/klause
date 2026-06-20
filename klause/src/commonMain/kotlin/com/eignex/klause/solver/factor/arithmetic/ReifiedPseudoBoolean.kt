package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.factor.ReifiedFactor
import com.eignex.klause.solver.factor.bool.PseudoBooleanSumFactor
import com.eignex.klause.solver.factor.bool.pbDistance
import com.eignex.klause.solver.factor.bool.reifiedBoolApply
import com.eignex.klause.solver.factor.bool.reifiedBoolDelta
import com.eignex.klause.solver.factor.bool.reifiedBoolUpdateBreakMake
import com.eignex.klause.solver.factor.bool.reifiedDegree
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * `auxBoolVar ↔ (Σ weights(i) * lit(i) ⟨op⟩ bound)`. Payload at `intPayload(factorId)` is the
 * current weighted sum.
 */
class ReifiedPseudoBoolean(override val auxBoolVar: Int, weights: IntArray, literals: IntArray, op: PbOp, bound: Int) :
    PseudoBooleanSumFactor(weights, literals, op, bound, excludedVar = auxBoolVar),
    ReifiedFactor,
    ReifiedPseudoBooleanPropagator,
    ReifiedPseudoBooleanInvariant {

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

    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) =
        reifiedBoolUpdateBreakMake(state, factorId, flippedVar, auxBoolVar, signedByVar, boolVars, ::degreeFor)
}
