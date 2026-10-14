package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.theory.qfnra.PolynomialTheoryService
import com.eignex.klause.theory.qfnra.QfNraAssignment
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.theory.EncodingAttribution
import com.eignex.klause.theory.TheoryRealId
import com.eignex.klause.theory.Encoder
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.TheoryEncoding
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation

internal class SharedPolynomialModel private constructor(
    val source: Problem,
    val finite: Problem,
    val service: PolynomialTheoryService,
    val integerColumns: Map<Int, Int>,
    val finiteFactorIndices: List<Int>,
    val encodingAttributions: List<EncodingAttribution>,
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
            return source.preparePolynomialExecutionPlan(sourceFormula, formulaForFactor).polynomialModel
        }

        internal fun lower(
            source: Problem,
            sourceFormula: PolynomialRealFormula?,
            formulaForFactor: (Factor) -> PolynomialRealFormula?,
            projections: PreparedFactorProjections,
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
            val attributions = ArrayList<EncodingAttribution>()
            for ((index, factor) in source.factors.withIndex()) {
                val explicit = formulaForFactor(factor)
                val continuous = explicit != null || factor is ContinuousProduct || factor is RealProduct ||
                    factor.variables.reals.isNotEmpty() ||
                    factor.linearForm?.rows?.any { it.constants is RealConstants } == true
                if (!continuous) {
                    kept += index
                    continue
                }
                val selected = if (explicit == null) projections.encoder(index, EncodingTarget.POLYNOMIAL)
                else Encoder(EncoderSupport.EQUIVALENT) { _, encoding ->
                    encoding.polynomial(explicit)
                    null
                }
                val emitted = ArrayList<PolynomialRealFormula>()
                val integerMapping = LinkedHashMap<SourceIntId, TheoryRealId>()
                val realMapping = LinkedHashMap<SourceRealId, TheoryRealId>()
                val auxiliaries = ArrayList<TheoryRealId>()
                val encoding = object : TheoryEncoding {
                    override val target = EncodingTarget.POLYNOMIAL
                    override val sourceFactorIndex = index
                    override val cancellation = Cancellation.Never
                    override fun fixedInteger(id: SourceIntId): BigInt? = null
                    override fun fixedBoolean(id: SourceBoolId): Boolean? = null
                    override fun linear(form: LinearForm): Unit = error("polynomial target requires a formula")
                    override fun polynomial(formula: PolynomialRealFormula) { emitted += formula }
                    override fun integer(id: SourceIntId): PolynomialExpression {
                        require(id.index in factor.intVars)
                        return integer(id.index).also {
                            integerMapping[id] = TheoryRealId(integerIds.getValue(id.index))
                        }
                    }
                    override fun real(id: SourceRealId): PolynomialExpression {
                        require(id.index in factor.variables.reals)
                        realMapping[id] = TheoryRealId(id.index)
                        return real(id.index)
                    }
                    override fun conditional(
                        condition: PolynomialRealFormula,
                        whenTrue: PolynomialExpression,
                        whenFalse: PolynomialExpression,
                    ): PolynomialExpression {
                        val before = lowering.realCount
                        return lowering.conditional(condition, whenTrue, whenFalse).also {
                            for (id in before until lowering.realCount) auxiliaries += TheoryRealId(id)
                        }
                    }
                }
                if (selected.install(factor, encoding) != null || emitted.isEmpty()) return null
                formulas += emitted
                attributions += EncodingAttribution(index, factor.boolVars.map(::SourceBoolId),
                    factor.intVars.map(::SourceIntId), factor.variables.reals.map(::SourceRealId),
                    integerMapping, realMapping, auxiliaries)
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
            val fixedReals = (0 until source.numRealVars).mapNotNull { id ->
                source.realLower[id].takeIf { it.isFinite() && it == source.realUpper[id] }
                    ?.let { id to requireNotNull(BigFraction.ofDouble(it)) }
            }.toMap()
            return SharedPolynomialModel(
                source,
                Problem(
                    source.numBoolVars, source.numIntVars, source.declaredIntDomains,
                    Array(kept.size) { source.factors[kept[it]] },
                    source.impliedFactorMask?.let { mask -> BooleanArray(kept.size) { mask[kept[it]] } },
                    source.hasSymmetryBreaking, settings = source.settings,
                ),
                PolynomialTheoryService(formula, source.numBoolVars, lowering.realCount, fixedReals),
                integerIds.toMap(),
                kept.toList(),
                attributions.toList(),
            )
        }

        private fun constant(value: BigFraction): PolynomialExpression = PolynomialRealLowering.constant(value)

        private fun bound(variable: PolynomialExpression, relation: PolynomialRelation, value: Double) =
            PolynomialRealFormula.Atom(variable, relation, constant(requireNotNull(BigFraction.ofDouble(value))))
    }
}
