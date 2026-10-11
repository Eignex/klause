package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.Term
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.theory.qfnra.PolynomialTheoryService
import com.eignex.klause.theory.qfnra.QfNraAssignment
import com.eignex.klause.util.BIG_ONE

internal class SharedPolynomialModel private constructor(
    val source: Problem,
    val finite: Problem,
    val service: PolynomialTheoryService,
    val integerColumns: Map<Int, Int>,
) {
    fun capture(assignment: QfNraAssignment, bools: BooleanArray, ints: LongArray): Sample {
        val reals = List(source.numRealVars) { assignment.realValue(it) }
        return Sample(
            bools, ints,
            DoubleArray(reals.size) { index ->
                val value = reals[index]
                value.rational?.toDouble() ?: (value.lowerBound.toDouble() / 2 + value.upperBound.toDouble() / 2)
            },
            algebraicReals = reals,
        )
    }

    companion object {
        fun assemble(
            source: Problem,
            sourceFormula: PolynomialRealFormula? = null,
            formulaForFactor: (Factor) -> PolynomialRealFormula? = { null },
        ): SharedPolynomialModel? {
            if ((0 until source.numIntVars).any { !source.intBounds.hasLower(it) || !source.intBounds.hasUpper(it) }) {
                return null
            }
            val integerIds = LinkedHashMap<Int, Int>()
            fun integer(id: Int): PolynomialExpression = PolynomialExpression.Variable(
                SourceRealId(integerIds.getOrPut(id) { source.numRealVars + integerIds.size }),
            )
            fun real(id: Int): PolynomialExpression = PolynomialExpression.Variable(SourceRealId(id))
            val lowering = PolynomialRealLowering(source.numRealVars + source.numIntVars)
            val formulas = ArrayList<PolynomialRealFormula>()
            sourceFormula?.let(formulas::add)
            val kept = ArrayList<Int>()
            for ((index, factor) in source.factors.withIndex()) {
                val explicit = formulaForFactor(factor)
                if (explicit != null) {
                    formulas += explicit
                } else if (factor is RealProduct) {
                    formulas += PolynomialRealFormula.Atom(
                        PolynomialExpression.Product(listOf(integer(factor.intOperand), real(factor.realOperand))),
                        PolynomialRelation.EQ, real(factor.result),
                    )
                } else if (factor.variables.reals.isNotEmpty() ||
                    factor.linearForm?.rows?.any { it.constants is RealConstants } == true
                ) {
                    val form = factor.linearForm ?: return null
                    if (form is LinearForm.Relaxation) return null
                    val rows = form.rows.map { row -> polynomialRow(row, ::integer, ::real, lowering) }
                    formulas += if (form is LinearForm.Disjunction) PolynomialRealFormula.Or(rows)
                    else PolynomialRealFormula.And(rows)
                } else {
                    kept += index
                }
            }
            for (id in 0 until source.numRealVars) {
                val lower = source.realLower[id]
                val upper = source.realUpper[id]
                if (lower.isNaN() || upper.isNaN()) return null
                if (lower == Double.POSITIVE_INFINITY || upper == Double.NEGATIVE_INFINITY) {
                    formulas += PolynomialRealFormula.Constant(false)
                }
                if (lower.isFinite()) formulas += bound(real(id), PolynomialRelation.GE, lower)
                if (upper.isFinite()) formulas += bound(real(id), PolynomialRelation.LE, upper)
            }
            val formula = lowering.formula(formulas)
            return SharedPolynomialModel(
                source,
                Problem(
                    source.numBoolVars, source.numIntVars, source.declaredIntDomains,
                    Array(kept.size) { source.factors[kept[it]] },
                    source.impliedFactorMask?.let { mask -> BooleanArray(kept.size) { mask[kept[it]] } },
                    source.hasSymmetryBreaking, settings = source.settings,
                ),
                PolynomialTheoryService(formula, source.numBoolVars, lowering.realCount),
                integerIds.toMap(),
            )
        }

        private fun polynomialRow(
            row: LinearRow,
            integer: (Int) -> PolynomialExpression,
            real: (Int) -> PolynomialExpression,
            lowering: PolynomialRealLowering,
        ): PolynomialRealFormula {
            val terms = List(row.size) { index ->
                val ref = row.ref(index)
                val value = when {
                    Term.isInt(ref) -> integer(Term.intVar(ref))
                    Term.isReal(ref) -> real(Term.realVar(ref))
                    else -> {
                        val literal = Term.lit(ref)
                        val boolean = PolynomialRealFormula.BooleanVariable(SourceBoolId(literal ushr 1))
                        val condition = if (literal and 1 == 0) boolean else PolynomialRealFormula.Not(boolean)
                        lowering.conditional(condition, constant(BigFraction.ONE), constant(BigFraction.ZERO))
                    }
                }
                val coefficient = when (val constants = row.constants) {
                    is IntegralConstants -> BigFraction.of(constants.exactCoeff(index), BIG_ONE)
                    is RealConstants -> requireNotNull(BigFraction.ofDouble(
                        if (index < constants.intCoefficients.size) constants.intCoefficients.at(index)
                        else constants.realCoefficients.at(index - constants.intCoefficients.size),
                    ))
                }
                PolynomialExpression.Product(listOf(constant(coefficient), value))
            }
            val rhs = when (val constants = row.constants) {
                is IntegralConstants -> BigFraction.of(constants.exactBound, BIG_ONE)
                is RealConstants -> requireNotNull(BigFraction.ofDouble(constants.bound))
            }
            val relation = when (row.relation) {
                LinearOp.EQ -> PolynomialRelation.EQ
                LinearOp.LE -> if (row.strict) PolynomialRelation.LT else PolynomialRelation.LE
                LinearOp.GE -> if (row.strict) PolynomialRelation.GT else PolynomialRelation.GE
                LinearOp.NE -> PolynomialRelation.NE
            }
            val atom = PolynomialRealFormula.Atom(PolynomialExpression.Sum(terms), relation, constant(rhs))
            return if (row.activator == LinearRow.ALWAYS) atom else PolynomialRealLowering.equivalent(
                PolynomialRealFormula.BooleanVariable(SourceBoolId(row.activator)), atom,
            )
        }

        private fun constant(value: BigFraction): PolynomialExpression = PolynomialRealLowering.constant(value)

        private fun bound(variable: PolynomialExpression, relation: PolynomialRelation, value: Double) =
            PolynomialRealFormula.Atom(variable, relation, constant(requireNotNull(BigFraction.ofDouble(value))))
    }
}
