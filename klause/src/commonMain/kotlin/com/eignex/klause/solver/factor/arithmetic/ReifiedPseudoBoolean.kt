package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.factor.ReifiedFactor
import com.eignex.klause.solver.factor.bool.PseudoBooleanSumFactor
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits
import com.eignex.klause.solver.localsearch.LocalSearchState

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

    override fun asPropagator(): Propagator =
        ReifiedPseudoBooleanPropagator(auxBoolVar, weights, literals, op, bound, boolVars, intVars)

    override fun asInvariant(): Invariant =
        ReifiedPseudoBooleanInvariant(auxBoolVar, weights, literals, op, bound, boolVars, intVars)
}
