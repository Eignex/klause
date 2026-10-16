package com.eignex.klause.factor.bool

import com.eignex.klause.factor.bool.internals.exactPbConstants
import com.eignex.klause.factor.bool.internals.pseudoBooleanKey
import com.eignex.klause.factor.bool.internals.validatePseudoBoolean
import com.eignex.klause.factor.litVars
import com.eignex.klause.ir.BoolVars
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.FactorReduction.Rewrite
import com.eignex.klause.ir.FactorReduction.Unchanged
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.constsOf
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.BigInt
import kotlin.concurrent.Volatile

/**
 * `Σ weights(i)·literals(i) ⟨op⟩ bound` over Boolean literals (each contributing its weight when
 * true, 0 when false). Terms pair [constants] with [literals]; [op] compares the sum with the exact
 * right-hand side. Consumers choose their own arithmetic width.
 */
class PseudoBoolean private constructor(
    override val constants: IntegralConstants,
    literals: IntArray,
    val op: PbOp,
) :
    Factor,
    LinearRow {

    /** Weighted Boolean literals, retaining repetitions and polarity. */
    val literals: IntArray = literals.copyOf()
    private val longWeights: LongArray? = (constants as? IntegerConstants)?.coeffs

    /** Coefficients and bound with 64-bit constants, or null when any declared constant is wider. */
    val integerConstants: IntegerConstants? get() = constants as? IntegerConstants

    /** Coefficients for a row whose declared constants fit [Long]. */
    val weights: LongArray get() =
        requireNotNull(longWeights) { "pseudo-Boolean coefficients require exact arithmetic" }

    override val bound: Long get() =
        requireNotNull(integerConstants) { "pseudo-Boolean bound requires exact arithmetic" }.bound

    /** Retain a weighted-literal relation with signed-word constants. */
    constructor(weights: LongArray, literals: IntArray, op: PbOp, bound: Long) :
        this(IntegerConstants(constsOf(weights.copyOf()), bound), literals, op)

    /** Retain a weighted-literal relation with arbitrary-width integral constants. */
    constructor(weights: Array<BigInt>, literals: IntArray, op: PbOp, bound: BigInt) :
        this(exactPbConstants(weights, bound), literals, op)

    init {
        validatePseudoBoolean(constants, this.literals)
    }

    override val variables: VarList = BoolVars(literals.litVars())

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.PSEUDO_BOOLEAN, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        if (integerConstants != null) hashRemappedKey(FactorKind.PSEUDO_BOOLEAN, mapping, ::buildKey)
        else remap(mapping).structuralKey().hashCode()

    private fun buildKey(sink: KeySink) {
        pseudoBooleanKey(sink, constants, literals, op)
    }

    override fun remap(mapping: VarRemap): Factor = PseudoBoolean(constants, mapping.lits(literals), op)

    // Unit weights make `Σ 1·lit ⟨op⟩ bound` a plain count of true literals, i.e. a [Cardinality] — a
    // single canonical form that shares its dedicated counting propagator and lets an equivalent
    // pseudo-Boolean and cardinality dedup. (Coefficient strengthening first GCD-reduces equal weights to
    // unit, so this also catches `Σ c·lit`.) Solution-set exact; a vacuous bound drops the factor, while
    // an infeasible one is left to propagation. Mixed-polarity literals carry over — [Cardinality] counts
    // literals, not variables.
    override fun structuralReduce(domains: Array<IntDomain>): FactorReduction {
        val c = integerConstants ?: return Unchanged
        if ((0 until size).any { c.coeff(it) != 1L }) return Unchanged
        val n = literals.size
        return when (op) {
            PbOp.LE -> when {
                // #true ≤ n always holds
                bound >= n -> Rewrite(emptyList())

                // infeasible; leave to propagation
                bound < 0 -> Unchanged

                else -> Rewrite(listOf(Cardinality(literals, min = 0, max = bound.toInt())))
            }

            PbOp.GE -> when {
                // #true ≥ 0 always holds
                bound <= 0 -> Rewrite(emptyList())

                // infeasible
                bound > n -> Unchanged

                else -> Rewrite(listOf(Cardinality(literals, min = bound.toInt(), max = n)))
            }

            PbOp.EQ -> if (bound in 0..n.toLong()) {
                Rewrite(listOf(Cardinality(literals, min = bound.toInt(), max = bound.toInt())))
            } else {
                Unchanged // infeasible
            }
        }
    }

    // The factor *is* its own exact linear row over its Boolean literals, read by presolve with no
    // allocation.
    override val relation: LinearOp = when (op) {
        PbOp.LE -> LinearOp.LE
        PbOp.GE -> LinearOp.GE
        PbOp.EQ -> LinearOp.EQ
    }
    override val size: Int get() = literals.size
    override fun ref(k: Int): Int = Term.ofLit(literals[k])
    override val isIntegerOnly: Boolean get() = false

    @Volatile
    private var cachedForm: LinearForm? = null

    override val linearForm: LinearForm
        get() = cachedForm ?: LinearForm.Conjunction(listOf(this)).also { cachedForm = it }
}
