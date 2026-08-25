package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.ReifiedFactor
import com.eignex.klause.factor.bool.internals.pbDegree
import com.eignex.klause.factor.bool.internals.pbHolds
import com.eignex.klause.factor.litVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.addExact
import com.eignex.klause.lp.subExact
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.BoolVars
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.VarRemap
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey

/**
 * `auxBoolVar ↔ (Σ weights(i) * lit(i) ⟨op⟩ bound)`. Payload at `intPayload(factorId)` is the
 * current weighted sum. Terms pair [weights] with [literals]; the sum is compared by [op] against
 * [bound].
 */
class ReifiedPseudoBoolean(
    override val auxBoolVar: Int,
    val weights: LongArray,
    val literals: IntArray,
    val op: PbOp,
    val bound: Long,
) : ReifiedFactor {

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

    override fun asPropagator(): Propagator =
        ReifiedPseudoBooleanPropagator(auxBoolVar, weights, literals, op, bound, boolVars, intVars)

    override fun asInvariant(): Invariant =
        ReifiedPseudoBooleanInvariant(auxBoolVar, weights, literals, op, bound, boolVars)

    /**
     * Indicator rows for `auxBoolVar ↔ (Σ weights·literal ⟨op⟩ bound)` over Boolean literals. The big-M
     * comes from the declared `[0, 1]` ranges (so the rows are global / CORE), and for `EQ` only the
     * `aux = 1 ⇒ L = bound` direction is emitted (its complement is a disjunction with no single LP cut).
     */
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        val sum = BoolReifiedSum.fold(builder, literals, weights)
        val a = builder.boolColumn(auxBoolVar)
        val b = subExact(bound, sum.constant)
        when (op) {
            PbOp.LE -> {
                val m1 = maxOf(0L, subExact(sum.lMax, b)) // aux=1 ⇒ L ≤ bound
                sum.reifiedRow(builder, a, m1, LinearOp.LE, addExact(b, m1))
                val m2 = maxOf(0L, subExact(addExact(b, 1L), sum.lMin)) // aux=0 ⇒ L ≥ bound+1
                sum.reifiedRow(builder, a, m2, LinearOp.GE, addExact(b, 1L))
            }

            PbOp.GE -> {
                val m1 = maxOf(0L, subExact(b, sum.lMin)) // aux=1 ⇒ L ≥ bound
                sum.reifiedRow(builder, a, -m1, LinearOp.GE, subExact(b, m1))
                val m2 = maxOf(0L, subExact(sum.lMax, subExact(b, 1L))) // aux=0 ⇒ L ≤ bound-1
                sum.reifiedRow(builder, a, -m2, LinearOp.LE, subExact(b, 1L))
            }

            PbOp.EQ -> {
                val mHi = maxOf(0L, subExact(sum.lMax, b)) // aux=1 ⇒ L ≤ bound
                sum.reifiedRow(builder, a, mHi, LinearOp.LE, addExact(b, mHi))
                val mLo = maxOf(0L, subExact(b, sum.lMin)) // aux=1 ⇒ L ≥ bound
                sum.reifiedRow(builder, a, -mLo, LinearOp.GE, subExact(b, mLo))
            }
        }
    }
}
