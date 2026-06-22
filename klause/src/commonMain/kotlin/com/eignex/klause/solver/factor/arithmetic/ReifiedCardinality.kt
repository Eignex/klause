package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.ReifiedFactor
import com.eignex.klause.solver.factor.compressViolation
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * `auxBoolVar ↔ ([min] ≤ #true [literals] ≤ [max])`. Created by the compiler when a
 * [com.eignex.klause.model.CardinalityExpr] / `AtMost` / `AtLeast` appears non-top-level so the
 * Tseitin lowering can treat its truth as a Boolean literal. Payload at `longPayload(factorId)`
 * is the count of true literals, mirrored from `Cardinality`.
 */
class ReifiedCardinality(override val auxBoolVar: Int, val literals: IntArray, val min: Int, val max: Int) :
    ReifiedFactor {

    init {
        require(min in 0..max) { "Cardinality bounds invalid: $min..$max" }
        require(max <= literals.size) { "max ($max) exceeds literal count (${literals.size})" }
    }

    override val intVars: IntArray = EmptyIntArray

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        ReifiedCardinality(boolMap[auxBoolVar], literals.remapLits(boolMap), min, max)

    /** `Cardinality.structuralKey` plus the reifying [auxBoolVar]; the distinct factor kind keeps it
     *  disjoint from a bare cardinality's key (#443). */
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.REIFIED_CARDINALITY) {
        int(auxBoolVar)
        int(min)
        int(max)
        sortedInts(literals)
    }

    override val boolVars: IntArray = literals.litVars(auxBoolVar)

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean {
        val sum = state.longPayload[factorId]
        return sum >= min && sum <= max
    }

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        compressViolation(countDistance(state.longPayload[factorId]), softCap)

    private fun countDistance(n: Long): Long = (if (n < min) min - n else 0L) + (if (n > max) n - max else 0L)

    override fun asPropagator(): Propagator =
        ReifiedCardinalityPropagator(auxBoolVar, literals, min, max, boolVars, intVars)

    override fun asInvariant(): Invariant = ReifiedCardinalityInvariant(auxBoolVar, literals, min, max, boolVars)
}
