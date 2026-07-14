package com.eignex.klause.factor.bool

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.litVars
import com.eignex.klause.factor.remapLits
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.LinearRow
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.EmptyLongArray

/**
 * `Σ weights(i) * lit(i) ⟨op⟩ bound` over Boolean literals (each contributing its weight when
 * true, 0 when false). Payload at `intPayload(factorId)` is the current weighted sum. Terms pair
 * [weights] with [literals]; the sum is compared by [op] against [bound].
 */
class PseudoBoolean(val weights: LongArray, val literals: IntArray, val op: PbOp, val bound: Long) : Factor {

    override val intVars: IntArray = EmptyIntArray

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.PSEUDO_BOOLEAN, ::buildKey)

    override fun remapStructuralHash(boolMap: IntArray, intMap: IntArray): Int =
        hashRemappedKey(FactorKind.PSEUDO_BOOLEAN, boolMap, intMap, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.enum(op)
        sink.long(bound)
        sink.pairsByLitKey(literals) { weights[it] }
    }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor =
        PseudoBoolean(weights, literals.remapLits(boolMap), op, bound)

    override val boolVars: IntArray = literals.litVars()

    override val extendsObjectiveCone: Boolean = true

    override fun asPropagator(): Propagator = PseudoBooleanPropagator(boolVars, intVars, weights, literals, op, bound)

    override fun asInvariant(): Invariant = PseudoBooleanInvariant(boolVars, weights, literals, op, bound)

    private val linearOp: LinearOp = when (op) {
        PbOp.LE -> LinearOp.LE
        PbOp.GE -> LinearOp.GE
        PbOp.EQ -> LinearOp.EQ
    }

    /** LP relaxation: the feasibility-defining row `Σ weights·literals ⟨op⟩ bound`. */
    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        builder.boolRow(literals, weights, linearOp, bound)
    }

    /** Exact linear view: the row `Σ weights·literals ⟨op⟩ bound` over its Boolean literals. */
    override val linearRows: List<LinearRow> by lazy {
        listOf(LinearRow(EmptyLongArray, EmptyIntArray, linearOp, bound, weights, literals))
    }
}
