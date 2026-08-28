package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.validatePseudoBoolean
import com.eignex.klause.factor.litVars
import com.eignex.klause.ir.BoolVars
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.FactorReduction.Rewrite
import com.eignex.klause.ir.FactorReduction.Unchanged
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.model.PbOp

/**
 * `Σ weights(i) * lit(i) ⟨op⟩ bound` over Boolean literals (each contributing its weight when
 * true, 0 when false). Payload at `intPayload(factorId)` is the current weighted sum. Terms pair
 * [weights] with [literals]; the sum is compared by [op] against [bound].
 */
class PseudoBoolean(weights: LongArray, literals: IntArray, val op: PbOp, override val bound: Long) :
    Factor,
    LinearRow {

    val weights: LongArray = weights.copyOf()
    val literals: IntArray = literals.copyOf()

    init {
        validatePseudoBoolean(this.weights, this.literals)
    }

    override val variables: VarList = BoolVars(literals.litVars())

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.PSEUDO_BOOLEAN, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        hashRemappedKey(FactorKind.PSEUDO_BOOLEAN, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.enum(op)
        sink.long(bound)
        sink.pairsByLitKey(literals) { weights[it] }
    }

    override fun remap(mapping: VarRemap): Factor = PseudoBoolean(weights, mapping.lits(literals), op, bound)

    // Unit weights make `Σ 1·lit ⟨op⟩ bound` a plain count of true literals, i.e. a [Cardinality] — a
    // single canonical form that shares its dedicated counting propagator and lets an equivalent
    // pseudo-Boolean and cardinality dedup. (Coefficient strengthening first GCD-reduces equal weights to
    // unit, so this also catches `Σ c·lit`.) Solution-set exact; a vacuous bound drops the factor, while
    // an infeasible one is left to propagation. Mixed-polarity literals carry over — [Cardinality] counts
    // literals, not variables.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        if (weights.any { it != 1L }) return Unchanged
        val n = literals.size
        return when (op) {
            PbOp.LE -> when {
                bound >= n -> Rewrite(emptyList())

                // #true ≤ n always holds
                bound < 0 -> Unchanged

                // infeasible; leave to propagation
                else -> Rewrite(listOf(Cardinality(literals, min = 0, max = bound.toInt())))
            }

            PbOp.GE -> when {
                bound <= 0 -> Rewrite(emptyList())

                // #true ≥ 0 always holds
                bound > n -> Unchanged

                // infeasible
                else -> Rewrite(listOf(Cardinality(literals, min = bound.toInt(), max = n)))
            }

            PbOp.EQ -> if (bound in 0..n.toLong()) {
                Rewrite(listOf(Cardinality(literals, min = bound.toInt(), max = bound.toInt())))
            } else {
                Unchanged // infeasible
            }
        }
    }

    override fun linearize(builder: RelaxationBuilder, factorId: Int) {
        builder.boolRow(literals, weights, relation, bound)
    }

    // The factor *is* its own exact linear row over its Boolean literals, read by presolve with no
    // allocation. [relation] maps the native [PbOp] onto the row's [LinearOp] (the two never clash).
    override val relation: LinearOp = when (op) {
        PbOp.LE -> LinearOp.LE
        PbOp.GE -> LinearOp.GE
        PbOp.EQ -> LinearOp.EQ
    }
    override val size: Int get() = literals.size
    override fun ref(k: Int): Int = Term.ofLit(literals[k])
    override fun coeff(k: Int): Long = weights[k]
    override val isIntegerOnly: Boolean get() = false
    override val linearRows: List<LinearRow> get() = listOf(this)
}
