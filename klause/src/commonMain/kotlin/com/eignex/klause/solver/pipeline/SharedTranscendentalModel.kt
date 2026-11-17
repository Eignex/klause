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
import com.eignex.klause.solver.PropagationCapability
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.theory.EncoderDecline
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
    val finiteIndices = source.factors.indices.filter {
        source.factors[it].variables.reals.isEmpty() &&
            projections.capabilities(it).propagation != PropagationCapability.UNSUPPORTED
    }
    val finiteSet = finiteIndices.toSet()
    val encoded = source.factors.indices.filter { it !in finiteSet }
    var failedFactor: Int? = null
    var decline: PreparedPlanDecline? = when {
        (0 until source.numIntVars).any { !source.intBounds.hasLower(it) || !source.intBounds.hasUpper(it) } ->
            PreparedPlanDecline.OPEN_FINITE_INTEGER
        source.semantics.isApproximate || semantics.isApproximate -> PreparedPlanDecline.UNOWNED_SOURCE_COMPONENT
        else -> null
    }
    var completion: SharedTranscendentalModel? = null
    try {
        stop.polynomialSourceCheckpoint(1)
        if (decline == null) {
            val scope = encoded.filter { source.factors[it] !is ExactRealEquality }
            val request = projections.requestEncoders(EncodingTarget.POLYNOMIAL, scope,
                cancellation = stop, onDecline = {
                    decline = if (it == EncoderDecline.RESOURCE) PreparedPlanDecline.RESOURCE
                    else PreparedPlanDecline.POLYNOMIAL_ENCODING
                })
            if (request == null) {
                failedFactor = scope.firstOrNull { !projections.encoder(it, EncodingTarget.POLYNOMIAL).enforcesSource }
            }
            val selected = request?.let { scope.zip(it.encoders).toMap() }.orEmpty()
            val lowering = PolynomialRealLowering(numericCount)
            val formulas = ArrayList<PolynomialRealFormula>()
            val functionFormulas = arrayListOf(this.formula)
            val attributions = ArrayList<EncodingAttribution>()
            for (index in encoded) {
                if (decline != null) break
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
                    failedFactor = index
                    decline = if (refused == EncoderDecline.RESOURCE) {
                        PreparedPlanDecline.RESOURCE
                    } else PreparedPlanDecline.POLYNOMIAL_ENCODING
                    break
                }
                formulas += context.formulas
                attributions += context.attribution()
            }
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
                val finite = Problem.of(source.numBoolVars, source.numIntVars, source.declaredIntDomains,
                    Array(finiteIndices.size) { source.factors[finiteIndices[it]] },
                    source.impliedFactorMask?.let { mask ->
                        BooleanArray(finiteIndices.size) { mask[finiteIndices[it]] }
                    },
                    source.hasSymmetryBreaking, settings = source.settings, semantics = source.semantics)
                completion = SharedTranscendentalModel(source,
                    withFormula(formula, lowering.realCount - numericCount), finite, projections, finiteIndices,
                    attributions.toList())
            }
        }
    } catch (_: PolynomialSourceInterrupted) {
        decline = PreparedPlanDecline.RESOURCE
    }
    val support = if (completion == null) {
        val unavailable = ComponentStrategySupport(ProcedureCompleteness.UNAVAILABLE, emptySet(),
            listOf(ExecutionDecline(ExecutionDeclineCause.PREPARATION, "transcendental", failedFactor, decline)))
        ComponentExecutionContract(unavailable, unavailable)
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
