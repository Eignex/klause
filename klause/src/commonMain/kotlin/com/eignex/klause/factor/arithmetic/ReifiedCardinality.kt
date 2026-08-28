package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.ReifiedFactor
import com.eignex.klause.factor.compressViolation
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

    override val variables: VarList = BoolVars(literals.litVars(auxBoolVar))

    override fun remap(mapping: VarRemap): Factor =
        ReifiedCardinality(mapping.bool(auxBoolVar), mapping.lits(literals), min, max)

    /** `Cardinality.structuralKey` plus the reifying [auxBoolVar]; the distinct factor kind keeps it
     *  disjoint from a bare cardinality's key. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REIFIED_CARDINALITY, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.REIFIED_CARDINALITY, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.boolVar(auxBoolVar)
        sink.int(min)
        sink.int(max)
        sink.sortedBoolLits(literals)
    }

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean {
        val sum = state.longPayload[factorId]
        return sum >= min && sum <= max
    }

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        compressViolation(countDistance(state.longPayload[factorId]), softCap)

    private fun countDistance(n: Long): Long = (if (n < min) min - n else 0L) + (if (n > max) n - max else 0L)
}
