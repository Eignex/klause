package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.factor.ReifiedFactor
import com.eignex.klause.solver.factor.bool.CardinalitySumFactor
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits

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

    override fun reifSignedFor(v: Int): Int = signedByVar[v]
}
