package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.ir.WideConsts
import com.eignex.klause.ir.constsOf
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.div
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.gcd
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.unaryMinus

/** An exact rational comparison over tagged integer and real source coordinates. */
class ExactLinear private constructor(
    private val references: IntArray,
    private val coefficients: Array<BigInt>,
    private val rhs: BigInt,
    override val relation: LinearOp,
    override val strict: Boolean,
    override val activator: Int,
) : Factor, LinearRow {
    override val size: Int get() = references.size
    override fun ref(k: Int): Int = references[k]
    override val constants: IntegralConstants = if (rhs.fitsLong() && coefficients.all { it.fitsLong() }) {
        IntegerConstants(constsOf(LongArray(coefficients.size) { coefficients[it].toLongExact() }), rhs.toLongExact())
    } else WideConstants(WideConsts(coefficients), rhs)
    override val linearForm: LinearForm = LinearForm.Conjunction(listOf(this))
    override val variables: MixedVars = MixedVars(
        boundInts = references.filter(Term::isInt).map(Term::intVar).toIntArray(),
        boolVars = if (activator == LinearRow.ALWAYS) intArrayOf() else intArrayOf(activator),
        reals = references.filter(Term::isReal).map(Term::realVar).toIntArray(),
    )

    override fun remap(mapping: VarRemap): ExactLinear = of(
        IntArray(size) { k ->
            val reference = ref(k)
            if (Term.isInt(reference)) Term.ofIntVar(mapping.int(Term.intVar(reference)))
            else Term.ofRealVar(mapping.real(Term.realVar(reference)))
        },
        coefficients.map { BigFraction.of(it, BIG_ONE) }, relation, BigFraction.of(rhs, BIG_ONE), strict,
        if (activator == LinearRow.ALWAYS) activator else mapping.bool(activator).also {
            require(it >= 0) { "removed exact row activator" }
        },
    )

    override fun structuralKey(): StructuralKey = key()

    override fun remapStructuralHash(mapping: VarRemap): Int = key(mapping).hashCode()

    internal fun key(mapping: VarRemap? = null): StructuralKey {
        val terms = LinkedHashMap<Pair<Boolean, Int>, BigInt>()
        for (index in references.indices) {
            val reference = references[index]
            val real = Term.isReal(reference)
            val id = if (real) Term.realVar(reference) else Term.intVar(reference)
            val image = if (real) mapping?.real(id) ?: id else mapping?.int(id) ?: id
            val coordinate = real to image
            terms[coordinate] = (terms[coordinate] ?: BIG_ZERO) + coefficients[index]
        }
        val kept = terms.filterValues { it != BIG_ZERO }
            .entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))
        return StructuralKey.of(FactorKind.EXACT_LINEAR) {
            enum(relation)
            bool(strict)
            int(if (activator == LinearRow.ALWAYS) activator else mapping?.bool(activator) ?: activator)
            ints(rhs.toString().map { it.code }.toIntArray())
            int(kept.size)
            for ((coordinate, coefficient) in kept) {
                bool(coordinate.first)
                int(coordinate.second)
                ints(coefficient.toString().map { it.code }.toIntArray())
            }
        }
    }

    /** Exact source construction; positive denominator clearing preserves the comparison direction. */
    companion object {
        /**
         * Coalesce tagged coordinates and clear denominators without approximation. [strict] keeps an
         * open real boundary; integer-only strict comparisons use the equivalent integral threshold.
         * [activator] reifies the whole comparison in both directions, or [LinearRow.ALWAYS] asserts it.
         */
        fun of(
            refs: IntArray,
            coefficients: List<BigFraction>,
            relation: LinearOp,
            rhs: BigFraction,
            strict: Boolean = false,
            activator: Int = LinearRow.ALWAYS,
        ): ExactLinear {
            require(!strict || relation == LinearOp.LE || relation == LinearOp.GE) { "strict row must be an inequality" }
            require(activator >= LinearRow.ALWAYS) { "invalid row activator" }

            require(refs.size == coefficients.size)
            require(refs.all { it >= 0 && (Term.isInt(it) || Term.isReal(it)) })
            val terms = LinkedHashMap<Int, BigFraction>()
            for (index in refs.indices) {
                terms[refs[index]] = (terms[refs[index]] ?: BigFraction.ZERO) + coefficients[index]
            }
            val kept = terms.filterValues { !it.isZero }
            val denominator = kept.values.fold(rhs.den) { scale, value -> scale / scale.gcd(value.den) * value.den }
            val multiplier = BigFraction.of(if (relation == LinearOp.GE) -denominator else denominator, BIG_ONE)
            val integerStrict = strict && kept.keys.none(Term::isReal)
            val bound = (rhs * multiplier).num - if (integerStrict) BIG_ONE else BIG_ZERO
            return ExactLinear(kept.keys.toIntArray(), kept.values.map { (it * multiplier).num }.toTypedArray(),
                bound, if (relation == LinearOp.GE) LinearOp.LE else relation, strict && !integerStrict, activator)
        }
    }
}
