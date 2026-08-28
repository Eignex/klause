package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.ReifiedFactor
import com.eignex.klause.factor.bool.internals.pbDegree
import com.eignex.klause.factor.bool.internals.pbHolds
import com.eignex.klause.factor.bool.internals.validatePseudoBoolean
import com.eignex.klause.factor.litVars
import com.eignex.klause.ir.BoolVars
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.model.PbOp

/**
 * `auxBoolVar ↔ (Σ weights(i) * lit(i) ⟨op⟩ bound)`. Payload at `intPayload(factorId)` is the
 * current weighted sum. Terms pair [weights] with [literals]; the sum is compared by [op] against
 * [bound].
 */
class ReifiedPseudoBoolean(
    override val auxBoolVar: Int,
    weights: LongArray,
    literals: IntArray,
    val op: PbOp,
    val bound: Long,
) : ReifiedFactor {

    init {
        require(literals.none { com.eignex.klause.ir.Lit.variable(it) == auxBoolVar }) {
            "reified pseudo-Boolean auxiliary variable $auxBoolVar occurs in its body"
        }
    }

    val weights: LongArray = weights.copyOf()
    val literals: IntArray = literals.copyOf()

    init {
        validatePseudoBoolean(this.weights, this.literals)
    }

    override val variables: VarList = BoolVars(literals.litVars(auxBoolVar))

    override fun remap(mapping: VarRemap): Factor =
        ReifiedPseudoBoolean(mapping.bool(auxBoolVar), weights, mapping.lits(literals), op, bound)

    /** `PseudoBoolean.structuralKey` plus the reifying [auxBoolVar]; the distinct factor kind keeps it
     *  disjoint from a bare pseudo-Boolean's key. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REIFIED_PSEUDO_BOOLEAN, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.REIFIED_PSEUDO_BOOLEAN, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.boolVar(auxBoolVar)
        sink.enum(op)
        sink.long(bound)
        sink.pairsByLitKey(literals) { weights[it] }
    }

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean =
        pbHolds(state.longPayload[factorId], op, bound)

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        pbDegree(state.longPayload[factorId], op, bound, softCap)
}
