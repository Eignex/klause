package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.constsOf
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.MixedVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.WideConstants
import com.eignex.klause.ir.WideConsts
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact
import com.eignex.klause.util.times
import com.eignex.klause.util.unaryMinus

/** An exact rational comparison over tagged integer and real source coordinates. */
class ExactLinear private constructor(
    private val references: IntArray,
    private val coefficients: Array<BigInt>,
    private val rhs: BigInt,
    override val relation: LinearOp,
) : Factor, LinearRow {
    override val size: Int get() = references.size
    override fun ref(k: Int): Int = references[k]
    override val constants: IntegralConstants = if (rhs.fitsLong() && coefficients.all { it.fitsLong() }) {
        IntegerConstants(constsOf(LongArray(coefficients.size) { coefficients[it].toLongExact() }), rhs.toLongExact())
    } else WideConstants(WideConsts(coefficients), rhs)
    override val linearForm: LinearForm = LinearForm.Conjunction(listOf(this))
    override val variables: MixedVars = MixedVars(
        boundInts = references.filter(Term::isInt).map(Term::intVar).toIntArray(),
        reals = references.filter(Term::isReal).map(Term::realVar).toIntArray(),
    )

    override fun remap(mapping: VarRemap): ExactLinear = of(
        IntArray(size) { k ->
            val reference = ref(k)
            if (Term.isInt(reference)) Term.ofIntVar(mapping.int(Term.intVar(reference)))
            else Term.ofRealVar(mapping.real(Term.realVar(reference)))
        },
        coefficients.map { BigFraction.of(it, BIG_ONE) }, relation, BigFraction.of(rhs, BIG_ONE),
    )

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.EXACT_LINEAR) {
        ints(references)
        enum(relation)
        for (value in coefficients + rhs) ints(value.toString().map { it.code }.toIntArray())
    }

    /** Exact source construction; positive denominator clearing preserves the comparison direction. */
    companion object {
        /** Coalesce repeated tagged coordinates before capturing the exact relation. */
        fun of(refs: IntArray, coefficients: List<BigFraction>, relation: LinearOp, rhs: BigFraction): ExactLinear {
            require(refs.size == coefficients.size)
            require(refs.all { it >= 0 && (Term.isInt(it) || Term.isReal(it)) })
            val terms = LinkedHashMap<Int, BigFraction>()
            for (index in refs.indices) terms[refs[index]] = (terms[refs[index]] ?: BigFraction.ZERO) + coefficients[index]
            val kept = terms.filterValues { !it.isZero }
            val denominator = kept.values.fold(rhs.den) { scale, value -> scale / scale.gcd(value.den) * value.den }
            val multiplier = BigFraction.of(if (relation == LinearOp.GE) -denominator else denominator, BIG_ONE)
            return ExactLinear(kept.keys.toIntArray(), kept.values.map { (it * multiplier).num }.toTypedArray(),
                (rhs * multiplier).num, if (relation == LinearOp.GE) LinearOp.LE else relation)
        }
    }
}
