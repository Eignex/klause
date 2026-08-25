package com.eignex.klause.factor.bool

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.litVars
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.lp.LinearRow
import com.eignex.klause.lp.RelaxationBuilder
import com.eignex.klause.lp.Term
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.FactorReduction
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.KeySink
import com.eignex.klause.solver.BoolVars
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.VarList
import com.eignex.klause.solver.VarRemap
import com.eignex.klause.solver.hashRemappedKey
import com.eignex.klause.solver.materializeKey

/**
 * `Σ weights(i) * lit(i) ⟨op⟩ bound` over Boolean literals (each contributing its weight when
 * true, 0 when false). Payload at `intPayload(factorId)` is the current weighted sum. Terms pair
 * [weights] with [literals]; the sum is compared by [op] against [bound].
 */
class PseudoBoolean(val weights: LongArray, val literals: IntArray, val op: PbOp, override val bound: Long) :
    Factor,
    LinearRow {

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
        if (weights.any { it != 1L }) return FactorReduction.Unchanged
        val n = literals.size
        return when (op) {
            PbOp.LE -> when {
                bound >= n -> FactorReduction.Rewrite(emptyList())

                // #true ≤ n always holds
                bound < 0 -> FactorReduction.Unchanged

                // infeasible; leave to propagation
                else -> FactorReduction.Rewrite(listOf(Cardinality(literals, min = 0, max = bound.toInt())))
            }

            PbOp.GE -> when {
                bound <= 0 -> FactorReduction.Rewrite(emptyList())

                // #true ≥ 0 always holds
                bound > n -> FactorReduction.Unchanged

                // infeasible
                else -> FactorReduction.Rewrite(listOf(Cardinality(literals, min = bound.toInt(), max = n)))
            }

            PbOp.EQ -> if (bound in 0..n.toLong()) {
                FactorReduction.Rewrite(listOf(Cardinality(literals, min = bound.toInt(), max = bound.toInt())))
            } else {
                FactorReduction.Unchanged // infeasible
            }
        }
    }

    override val extendsObjectiveCone: Boolean = true

    override fun asPropagator(): Propagator = PseudoBooleanPropagator(boolVars, intVars, weights, literals, op, bound)

    override fun asInvariant(): Invariant = PseudoBooleanInvariant(boolVars, weights, literals, op, bound)

    /** LP relaxation: the feasibility-defining row `Σ weights·literals ⟨op⟩ bound`. */
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
