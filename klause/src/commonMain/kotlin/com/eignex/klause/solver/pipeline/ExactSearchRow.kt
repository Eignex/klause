package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.LinearForm
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
import com.eignex.klause.util.times

internal class ExactSearchRow private constructor(
    private val refs: IntArray,
    private val coefficients: Array<BigInt>,
    private val rhs: BigInt,
    override val relation: LinearOp,
    override val strict: Boolean,
) : Factor, LinearRow {
    override val size: Int get() = refs.size
    override fun ref(k: Int): Int = refs[k]
    override val constants = WideConstants(WideConsts(coefficients), rhs)
    override val linearForm = LinearForm.Conjunction(listOf(this))
    override val variables = MixedVars(
        boundInts = refs.filter(Term::isInt).map(Term::intVar).toIntArray(),
        boolVars = refs.filter(Term::isBool).map { Term.lit(it) ushr 1 }.distinct().toIntArray(),
        reals = refs.filter(Term::isReal).map(Term::realVar).toIntArray(),
    )

    override fun remap(mapping: VarRemap): Factor = ExactSearchRow(
        IntArray(size) { k ->
            val ref = refs[k]
            when {
                Term.isInt(ref) -> Term.ofIntVar(mapping.int(Term.intVar(ref)))
                Term.isReal(ref) -> Term.ofRealVar(mapping.real(Term.realVar(ref)))
                else -> Term.ofLit(mapping.lit(Term.lit(ref)))
            }
        }, coefficients, rhs, relation, strict,
    )

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.EXACT_SEARCH_ROW) {
        ints(refs)
        enum(relation)
        bool(strict)
        for (value in coefficients + rhs) ints(value.toString().map { it.code }.toIntArray())
    }

    companion object {
        fun of(
            refs: IntArray,
            coefficients: List<BigFraction>,
            relation: LinearOp,
            rhs: BigFraction,
            strict: Boolean = false,
        ): ExactSearchRow {
            require(refs.size == coefficients.size)
            val denominator = coefficients.fold(rhs.den) { product, value -> product * value.den }
            return ExactSearchRow(
                refs.copyOf(), Array(refs.size) { (coefficients[it] * BigFraction.of(denominator, BIG_ONE)).num },
                (rhs * BigFraction.of(denominator, BIG_ONE)).num, relation, strict,
            )
        }
    }
}
