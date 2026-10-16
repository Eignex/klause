package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.ReifiedFactor
import com.eignex.klause.factor.bool.internals.ExactBooleanSum
import com.eignex.klause.factor.bool.internals.exactPbConstants
import com.eignex.klause.factor.bool.internals.pseudoBooleanKey
import com.eignex.klause.factor.bool.internals.validatePseudoBoolean
import com.eignex.klause.factor.litVars
import com.eignex.klause.ir.BoolVars
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
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
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.model.PbOp
import com.eignex.klause.util.BigInt

/**
 * `auxBoolVar ↔ (Σ weights(i)·literals(i) ⟨op⟩ bound)`. Terms pair [constants] with [literals],
 * retaining exact integral coefficients and the right-hand side in both reification directions.
 */
class ReifiedPseudoBoolean private constructor(
    override val auxBoolVar: Int,
    override val constants: IntegralConstants,
    literals: IntArray,
    /** Comparison of the weighted literal sum with its exact right-hand side. */
    val op: PbOp,
) : ReifiedFactor, LinearRow {

    init {
        require(literals.none { com.eignex.klause.ir.Lit.variable(it) == auxBoolVar }) {
            "reified pseudo-Boolean auxiliary variable $auxBoolVar occurs in its body"
        }
    }

    /** Weighted Boolean literals, retaining repetitions and polarity. */
    val literals: IntArray = literals.copyOf()
    private val longWeights: LongArray? = (constants as? IntegerConstants)?.coeffs
    private val exactBody by lazy { ExactBooleanSum(constants, this.literals) }

    /** Coefficients and bound with 64-bit constants, or null when any declared constant is wider. */
    val integerConstants: IntegerConstants? get() = constants as? IntegerConstants

    /** Coefficients for a row whose declared constants fit [Long]. */
    val weights: LongArray get() =
        requireNotNull(longWeights) { "pseudo-Boolean coefficients require exact arithmetic" }

    override val bound: Long get() =
        requireNotNull(integerConstants) { "pseudo-Boolean bound requires exact arithmetic" }.bound

    /** Retain a reified weighted-literal relation with signed-word constants. */
    constructor(auxBoolVar: Int, weights: LongArray, literals: IntArray, op: PbOp, bound: Long) :
        this(auxBoolVar, IntegerConstants(constsOf(weights.copyOf()), bound), literals, op)

    /** Retain a reified weighted-literal relation with arbitrary-width integral constants. */
    constructor(auxBoolVar: Int, weights: Array<BigInt>, literals: IntArray, op: PbOp, bound: BigInt) :
        this(auxBoolVar, exactPbConstants(weights, bound), literals, op)

    init {
        validatePseudoBoolean(constants, this.literals)
    }

    override val size: Int get() = literals.size
    override fun ref(k: Int): Int = Term.ofLit(literals[k])
    override val activator: Int get() = auxBoolVar
    override val relation: LinearOp get() = when (op) {
        PbOp.LE -> LinearOp.LE
        PbOp.GE -> LinearOp.GE
        PbOp.EQ -> LinearOp.EQ
    }
    override val isIntegerOnly: Boolean get() = false
    override val linearForm: LinearForm = LinearForm.Conjunction(listOf(this))

    override val variables: VarList = BoolVars(literals.litVars(auxBoolVar))

    override fun remap(mapping: VarRemap): Factor =
        ReifiedPseudoBoolean(mapping.bool(auxBoolVar), constants, mapping.lits(literals), op)

    /** `PseudoBoolean.structuralKey` plus the reifying [auxBoolVar]; the distinct factor kind keeps it
     *  disjoint from a bare pseudo-Boolean's key. */
    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.REIFIED_PSEUDO_BOOLEAN, ::buildKey)

    override fun remapStructuralHash(mapping: VarRemap): Int =
        if (integerConstants != null) hashRemappedKey(FactorKind.REIFIED_PSEUDO_BOOLEAN, mapping, ::buildKey)
        else materializeKey(FactorKind.REIFIED_PSEUDO_BOOLEAN) { sink ->
            sink.boolVar(mapping.bool(auxBoolVar))
            pseudoBooleanKey(sink, constants, mapping.lits(literals), op)
        }.hashCode()

    private fun buildKey(sink: KeySink) {
        sink.boolVar(auxBoolVar)
        pseudoBooleanKey(sink, constants, literals, op)
    }

    override fun holdsNow(state: LocalSearchState, factorId: Int): Boolean =
        exactBody.holds(exactBody.sum(state.assignment::boolValue), op)

    override fun residualNow(state: LocalSearchState, factorId: Int, softCap: Int): Int =
        exactBody.degree(exactBody.sum(state.assignment::boolValue), op, softCap)
}
