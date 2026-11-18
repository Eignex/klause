package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ExactRealEquality
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.TranscendentalExpression
import com.eignex.klause.ir.TranscendentalFormula
import com.eignex.klause.ir.TranscendentalProblem
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.lowering.functionFormula
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncoderFailure
import com.eignex.klause.theory.EncodingAttribution
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction

internal class SharedTranscendentalModel(
    val source: Problem,
    val problem: TranscendentalProblem,
    val finite: Problem,
    val projections: PreparedFactorProjections,
    val finiteFactorIndices: List<Int>,
    val attributions: List<EncodingAttribution>,
) {
    val finiteProjections = projections.projected(finite, finiteFactorIndices.toIntArray())
}

internal fun TranscendentalProblem.prepareFactorCompletion(
    projections: PreparedFactorProjections,
    cancellation: ExecutionControl,
): PreparedSourcePlan {
    val source = requireNotNull(factorModel)
    projections.requireSource(source)
    val stop = cancellation.encodingPreparationScope()
    val finiteIndices = source.factors.indices.filter { projections.hasFinitePeer(it) }
    val finiteSet = finiteIndices.toSet()
    val encoded = source.factors.indices.filter { it !in finiteSet }
    var failedFactor: Int? = null
    var encodingFailure: EncoderFailure? = null
    var activeFactor: Int? = null
    var decline: PreparedPlanDecline? = when {
        (0 until source.numIntVars).any { !source.intBounds.hasLower(it) || !source.intBounds.hasUpper(it) } ->
            PreparedPlanDecline.OPEN_FINITE_INTEGER
        source.semantics.relaxesFeasibility || semantics.relaxesFeasibility ->
            PreparedPlanDecline.UNOWNED_SOURCE_COMPONENT
        else -> null
    }
    var completion: SharedTranscendentalModel? = null
    try {
        stop.polynomialSourceCheckpoint(1)
        if (decline == null) {
            val scope = encoded.filter { source.factors[it] !is ExactRealEquality }
            val request = projections.requestEncoders(EncodingTarget.POLYNOMIAL, scope,
                cancellation = stop, onFailure = {
                    encodingFailure = it
                    failedFactor = it.factorIndex
                }, onDecline = {
                    decline = if (it == EncoderDecline.RESOURCE) PreparedPlanDecline.RESOURCE
                    else PreparedPlanDecline.POLYNOMIAL_ENCODING
                })
            val selected = request?.let { scope.zip(it.encoders).toMap() }.orEmpty()
            val lowering = PolynomialRealLowering(numericCount)
            val formulas = ArrayList<PolynomialRealFormula>()
            val functionFormulas = arrayListOf(this.formula)
            val attributions = ArrayList<EncodingAttribution>()
            for (index in encoded) {
                if (decline != null) break
                activeFactor = index
                stop.polynomialSourceCheckpoint(1)
                val factor = source.factors[index]
                if (factor is ExactRealEquality) {
                    functionFormulas += TranscendentalFormula.Atom(
                        TranscendentalExpression.Variable(SourceRealId(realCoordinates[factor.id.index])),
                        PolynomialRelation.EQ, TranscendentalExpression.ExactValue(factor.value))
                    continue
                }
                val context = PolynomialEncodingContext(index, source.factors[index], lowering,
                    requireNotNull(request).cancellation,
                    { integerCoordinates[it] }, { realCoordinates[it] })
                val refused = selected.getValue(index).install(source.factors[index], context)
                if (refused != null || context.formulas.isEmpty()) {
                    request.reportFailure(refused ?: EncoderDecline.UNSUPPORTED, index)
                    failedFactor = index
                    decline = if (refused == EncoderDecline.RESOURCE) {
                        PreparedPlanDecline.RESOURCE
                    } else PreparedPlanDecline.POLYNOMIAL_ENCODING
                    break
                }
                formulas += context.formulas
                attributions += context.attribution()
            }
            activeFactor = null
            if (decline == null) {
                fun bound(id: Int, value: Double, relation: PolynomialRelation) {
                    require(!value.isNaN()) { "NaN real declaration bound" }
                    if (value.isFinite()) formulas += PolynomialRealFormula.Atom(
                        PolynomialExpression.Variable(SourceRealId(realCoordinates[id])), relation,
                        PolynomialRealLowering.constant(requireNotNull(BigFraction.ofDouble(value))))
                    else if ((relation == PolynomialRelation.GE && value == Double.POSITIVE_INFINITY) ||
                        (relation == PolynomialRelation.LE && value == Double.NEGATIVE_INFINITY)) {
                        formulas += PolynomialRealFormula.Constant(false)
                    }
                }
                for (id in 0 until source.numRealVars) {
                    stop.polynomialSourceCheckpoint(1)
                    bound(id, source.realLower[id], PolynomialRelation.GE)
                    bound(id, source.realUpper[id], PolynomialRelation.LE)
                }
                val formula = TranscendentalFormula.And(functionFormulas +
                    lowering.formula(formulas).functionFormula(stop))
                val finite = source.finiteFactorPeer(finiteIndices)
                completion = SharedTranscendentalModel(source,
                    withFormula(formula, lowering.realCount - numericCount), finite, projections, finiteIndices,
                    attributions.toList())
            }
        }
    } catch (_: PolynomialSourceInterrupted) {
        failedFactor = activeFactor
        encodingFailure = EncoderFailure(EncodingTarget.POLYNOMIAL, EncoderDecline.RESOURCE, activeFactor)
        decline = PreparedPlanDecline.RESOURCE
    }
    val support = if (completion == null) {
        unavailableContract(ExecutionDeclineCause.PREPARATION, "transcendental", decline,
            encodingFailure, failedFactor)
    } else transcendentalExecutionContract()
    val execution = if (completion == null) PreparedExecution.Declined(source, requireNotNull(decline))
    else PreparedExecution.Transcendental(this, completion)
    val components = listOf(
        ExecutionComponentDescriptor("finite", ComponentResponsibility.FINITE, finiteIndices,
            (0 until source.numIntVars).toList(), emptyList(), (0 until source.numBoolVars).toList(),
            source.finiteComponentContract(finiteIndices, (0 until source.numIntVars).toList(), null,
                sourceCompletion = completion != null, exactNeighborhood = false, projections = projections)),
        ExecutionComponentDescriptor("transcendental", ComponentResponsibility.COMPLETION, encoded,
            emptyList(), (0 until source.numRealVars).toList(), execution = support),
    )
    return PreparedSourcePlan(this, execution, components, boolCount, integerCoordinates.size, realCoordinates.size,
        integerCoordinates = (0 until source.numIntVars).toList(),
        realCoordinates = (0 until source.numRealVars).toList(),
        operations = setOf(ExecutionOperation.SATISFY), factorProjections = projections)
}
