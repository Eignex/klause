package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.ReifiedFactor
import com.eignex.klause.factor.bool.internals.pbDegree
import com.eignex.klause.factor.bool.internals.pbHolds
import com.eignex.klause.factor.litVars
import com.eignex.klause.factor.remapLits
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.lp.Linearizer
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.util.EmptyIntArray

/**
 * `auxBoolVar ↔ (Σ weights(i) * lit(i) ⟨op⟩ bound)`. Payload at `intPayload(factorId)` is the
 * current weighted sum. Terms pair [weights] with [literals]; the sum is compared by [op] against
 * [bound].
 */
class ReifiedPseudoBoolean(
    override val auxBoolVar: Int,
    val weights: IntArray,
    val literals: IntArray,
    val op: PbOp,
    val bound: Int,
) : ReifiedFactor {

    override val intVars: IntArray = EmptyIntArray

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        ReifiedPseudoBoolean(boolMap[auxBoolVar], weights, literals.remapLits(boolMap), op, bound)

    /** `PseudoBoolean.structuralKey` plus the reifying [auxBoolVar]; the distinct factor kind keeps it
     *  disjoint from a bare pseudo-Boolean's key (#443). */
    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.REIFIED_PSEUDO_BOOLEAN) {
        int(auxBoolVar)
        enum(op)
        int(bound)
        pairsByKey(literals) { weights[it].toLong() }
    }

    override val boolVars: IntArray = literals.litVars(auxBoolVar)

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean =
        pbHolds(state.longPayload[factorId], op, bound)

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        pbDegree(state.longPayload[factorId], op, bound, softCap)

    override fun asPropagator(): Propagator =
        ReifiedPseudoBooleanPropagator(auxBoolVar, weights, literals, op, bound, boolVars, intVars)

    override fun asInvariant(): Invariant =
        ReifiedPseudoBooleanInvariant(auxBoolVar, weights, literals, op, bound, boolVars)

    override fun asLinearizer(): Linearizer = ReifiedPseudoBooleanLinearizer(literals, weights, op, bound, auxBoolVar)
}
