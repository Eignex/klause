package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move.BoolFlip
import com.eignex.klause.solver.factor.ReifiedFactor
import com.eignex.klause.solver.factor.bool.CardinalitySumFactor
import com.eignex.klause.solver.factor.bool.reifiedBoolApply
import com.eignex.klause.solver.factor.bool.reifiedBoolDelta
import com.eignex.klause.solver.factor.bool.reifiedBoolUpdateBreakMake
import com.eignex.klause.solver.factor.bool.reifiedDegree
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink

/**
 * `auxBoolVar ↔ (#true literals in [min, max])`. Created by the compiler when a
 * [com.eignex.klause.model.CardinalityExpr] / `AtMost` / `AtLeast` appears non-top-level so the
 * Tseitin lowering can treat its truth as a Boolean literal. Payload at `longPayload(factorId)`
 * is the count of true literals, mirrored from `Cardinality`.
 */
class ReifiedCardinality(override val auxBoolVar: Int, literals: IntArray, min: Int, max: Int) :
    CardinalitySumFactor(literals, min, max, excludedVar = auxBoolVar),
    ReifiedFactor,
    ReifiedCardinalityPropagator,
    ReifiedCardinalityInvariant {

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        ReifiedCardinality(boolMap[auxBoolVar], literals.remapLits(boolMap), min, max)

    /** `Cardinality.structuralKey` plus the reifying [auxBoolVar]; the `rcard` prefix keeps it disjoint
     *  from a bare cardinality's key (#443). */
    override fun structuralKey(): String = "rcard:$auxBoolVar:$min:$max:" + literals.sorted().joinToString(",")

    override val boolVars: IntArray = literals.litVars(auxBoolVar)

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean = holds(state.longPayload[factorId])

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        residual(state.longPayload[factorId], softCap)

    private fun degreeFor(n: Long, aux: Boolean, softCap: Int): Int =
        reifiedDegree(aux, holds(n)) { residual(n, softCap) }

    override fun deltaIfBoolFlipped(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        reifiedBoolDelta(state, factorId, boolVar, auxBoolVar, signedByVar, ::degreeFor)

    override fun applyBoolFlip(state: LocalSearchState, factorId: Int, boolVar: Int): Int =
        reifiedBoolApply(state, factorId, boolVar, auxBoolVar, signedByVar, ::degreeFor)

    override fun proposeRepairMoves(state: LocalSearchState, factorId: Int, sink: MoveSink) {
        val aux = state.assignment.boolValue(auxBoolVar)
        val n = state.longPayload[factorId]
        if (aux == holds(n)) return
        sink.addBoolFlip(auxBoolVar)
        val auxFlip = BoolFlip(auxBoolVar)
        val wantInRange = aux
        for (lit in literals) {
            val v = Lit.variable(lit)
            if (v == auxBoolVar) continue
            val isTrue = Lit.evaluate(lit, state.assignment.boolValue(v))
            val newN = n + if (isTrue) -1 else 1
            // Same-aux body flip: drives count toward the predicate matching current aux.
            if (wantInRange == holds(newN)) sink.addBoolFlip(v)
            // Toggle-driven sub-region exploration: pair aux flip with a body flip that
            // drives count toward the *opposite* predicate, so strategies can atomically
            // transition to the other reification side.
            if (wantInRange != holds(newN)) {
                sink.addCompound(listOf(auxFlip, BoolFlip(v)))
            }
        }
    }

    /** Recover the pre-flip count and aux value from the now-committed state, then walk
     *  each touched variable once applying the change in its break/make contribution. */
    override fun updateBoolBreakMakeForFlip(state: LocalSearchState, factorId: Int, flippedVar: Int) =
        reifiedBoolUpdateBreakMake(state, factorId, flippedVar, auxBoolVar, signedByVar, boolVars, ::degreeFor)
}
