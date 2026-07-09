package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.ReifiedFactor
import com.eignex.klause.factor.compressViolation
import com.eignex.klause.factor.litVars
import com.eignex.klause.factor.remapLits
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.subExact
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey
import com.eignex.klause.util.EmptyIntArray

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
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REIFIED_CARDINALITY, ::buildKey)

    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int =
        hashRemappedKey(FactorKind.REIFIED_CARDINALITY, boolMap, intMap, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.boolVar(auxBoolVar)
        sink.int(min)
        sink.int(max)
        sink.sortedBoolLits(literals)
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

    /**
     * Indicator rows for `auxBoolVar ↔ (min ≤ #true literals ≤ max)`. Only the `aux = 1 ⇒ (count ≥ min ∧
     * count ≤ max)` direction yields LP cuts (the `aux = 0` side is the disjunction `count < min ∨ count >
     * max`, whose hull is the whole interval), so two CORE rows are emitted with declared `[0, 1]` big-Ms.
     */
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        val sum = BoolReifiedSum.fold(builder, literals, weights = null)
        val a = builder.boolColumn(auxBoolVar)
        val lo = subExact(min.toLong(), sum.constant)
        val hi = subExact(max.toLong(), sum.constant)
        val mHi = maxOf(0L, subExact(sum.lMax, hi)) // aux=1 ⇒ count ≤ max
        sum.reifiedRow(builder, a, mHi, LinearOp.LE, addExact(hi, mHi))
        val mLo = maxOf(0L, subExact(lo, sum.lMin)) // aux=1 ⇒ count ≥ min
        sum.reifiedRow(builder, a, -mLo, LinearOp.GE, subExact(lo, mLo))
    }
}
