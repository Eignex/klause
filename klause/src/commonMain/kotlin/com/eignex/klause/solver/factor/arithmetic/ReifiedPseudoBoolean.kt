package com.eignex.klause.solver.factor.arithmetic

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Linearizer
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.ReifiedFactor
import com.eignex.klause.solver.factor.bool.internals.pbDegree
import com.eignex.klause.solver.factor.bool.internals.pbHolds
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits
import com.eignex.klause.solver.localsearch.LocalSearchState

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
