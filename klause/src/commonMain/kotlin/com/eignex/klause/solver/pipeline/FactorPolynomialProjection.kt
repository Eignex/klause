package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.lowering.polynomialRow
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.solver.validation.marginRow
import com.eignex.klause.solver.validation.statesStrictMargin
import com.eignex.klause.solver.validation.strictMargin
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncoderFailure
import com.eignex.klause.theory.EncoderSupport
import com.eignex.klause.theory.EncodingAttribution
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf

internal class FactorPolynomialProjection(
    val original: Problem,
    val model: Problem,
    val formula: NonlinearIntegerProblem,
    val projections: PreparedFactorProjections,
    attributions: List<EncodingAttribution>,
    val finiteOwnership: ComponentPlan?,
) {
    val attributions = attributions.toList()
    val finiteFactors: List<Int> = if (finiteOwnership == null) emptyList() else model.factors.indices.filter {
        finiteOwnership.factorOwner(it) == FactorOwner.CP
    }
    val refutesSource: Boolean = finiteOwnership == null ||
        PolynomialToleranceReading.of(original) == PolynomialToleranceReading.EXACT

    init {
        projections.requireSource(model)
        require(original.numIntVars == model.numIntVars && original.numRealVars == model.numRealVars &&
            original.numBoolVars == model.numBoolVars)
        require(original.numFactors == model.numFactors &&
            model.factors.indices.all { original.factors[it] === model.factors[it] })
        require(this.attributions.map { it.sourceFactorIndex } ==
            model.factors.indices.filter { finiteOwnership?.factorOwner(it) != FactorOwner.CP })
        require(formula.integerCoordinates == (0 until model.numIntVars).toList())
        require(formula.realCoordinates == (model.numIntVars until model.numIntVars + model.numRealVars).toList())
        require(formula.boolCount == model.numBoolVars && formula.semantics == original.semantics)
    }
}

internal fun Problem.integerPolynomialProjection(
    original: Problem,
    projections: PreparedFactorProjections,
    cancellation: ExecutionControl,
    onFailure: (EncoderFailure) -> Unit = {},
    onDecline: (EncoderDecline) -> Unit,
): FactorPolynomialProjection? {
    projections.requireSource(this)
    val stop = cancellation.encodingPreparationScope()
    var activeFactor: Int? = null
    fun checkpoint() {
        stop.charge(1)
        if (stop()) throw PolynomialSourceInterrupted()
    }
    require(numIntVars > 0)
    if (stop() || numIntVars > Int.MAX_VALUE - numRealVars) {
        onDecline(EncoderDecline.RESOURCE)
        return null
    }
    try {
        val fixedIntegers = HashMap<SourceIntId, BigInt>()
        val boundedIntegers = BooleanArray(numIntVars)
        for (id in 0 until numIntVars) {
            checkpoint()
            boundedIntegers[id] = intBounds.hasLower(id) && intBounds.hasUpper(id)
            if (boundedIntegers[id] && intBounds.lower(id) == intBounds.upper(id)) {
                fixedIntegers[SourceIntId(id)] = bigIntOf(intBounds.lower(id))
            }
        }
        val canKeepFinite = BooleanArray(numFactors) { index ->
            checkpoint()
            projections.hasFinitePeer(index) && projections.hasCompleteFiniteCheck(index) &&
                factors[index].intVars.all { boundedIntegers[it] }
        }
        val needsFinite = factors.indices.any { index ->
            checkpoint()
            if (!canKeepFinite[index]) return@any false
            val encoder = projections.encoder(index, EncodingTarget.POLYNOMIAL)
            !encoder.enforcesSource && (encoder.support != EncoderSupport.CONDITIONED ||
                encoder.requiredIntegers.any { it !in fixedIntegers } || encoder.requiredBooleans.isNotEmpty())
        }
        val ownership = if (needsFinite) ComponentPlan(
            this,
            Array(numIntVars) { if (boundedIntegers[it]) IntVariableOwner.CP else IntVariableOwner.THEORY },
            Array(numFactors) { if (canKeepFinite[it]) FactorOwner.CP else FactorOwner.THEORY },
            ProblemPipeline.UNSUPPORTED_OPEN,
            factorProjections = projections,
        ) else null
        val selected = factors.indices.filter { ownership?.factorOwner(it) != FactorOwner.CP }
        val request = projections.requestEncoders(EncodingTarget.POLYNOMIAL, selected,
            fixedIntegers = fixedIntegers, cancellation = stop, onFailure = onFailure, onDecline = onDecline)
            ?: return null
        val lowering = PolynomialRealLowering(numIntVars + numRealVars)
        val margin = if (ownership != null) original.semantics.effective.feasibilityTolerance.strictMargin()
        else BigFraction.ZERO
        val formulas = ArrayList<PolynomialRealFormula>()
        val attributions = ArrayList<EncodingAttribution>()
        fun integer(id: Int) = PolynomialExpression.Variable(SourceRealId(id))
        fun real(id: Int) = PolynomialExpression.Variable(SourceRealId(numIntVars + id))
        for ((position, index) in request.indices.withIndex()) {
            activeFactor = index
            checkpoint()
            val factor = factors[index]
            val encoding = PolynomialEncodingContext(index, factor, lowering, request.cancellation,
                integerCoordinate = { it }, realCoordinate = { numIntVars + it },
                fixedIntegers = request.fixedIntegers, fixedBooleans = request.fixedBooleans)
            val decline = if (factor.statesStrictMargin(margin)) {
                encoding.polynomial(polynomialRow((factor as Linear).marginRow(margin),
                    { encoding.integer(SourceIntId(it)) }, { encoding.real(SourceRealId(it)) },
                    encoding::conditional))
                null
            } else request.encoders[position].install(factor, encoding)
            if (decline != null || encoding.formulas.isEmpty()) {
                request.reportFailure(decline ?: EncoderDecline.UNSUPPORTED, index)
                onDecline(decline ?: EncoderDecline.UNSUPPORTED)
                return null
            }
            formulas += encoding.formulas
            attributions += encoding.attribution()
        }
        activeFactor = null
        fun bound(expression: PolynomialExpression, relation: PolynomialRelation, value: Long) {
            checkpoint()
            formulas += PolynomialRealFormula.Atom(expression, relation,
                PolynomialRealLowering.constant(BigFraction.ofLong(value)))
        }
        for (id in 0 until numIntVars) {
            checkpoint()
            if (ownership?.intOwner(id) == IntVariableOwner.CP) continue
            val value = integer(id)
            if (intBounds.hasLower(id)) bound(value, PolynomialRelation.GE, intBounds.lower(id))
            if (intBounds.hasUpper(id)) bound(value, PolynomialRelation.LE, intBounds.upper(id))
            val domain = intDomainOrNull(id) ?: continue
            if (domain.holeCount == 0L) continue
            val members = domain.spanOrNull(domain.holeCount)
            if (members != null) {
                val alternatives = ArrayList<PolynomialRealFormula>()
                members.forEach {
                    checkpoint()
                    alternatives += PolynomialRealFormula.Atom(value, PolynomialRelation.EQ,
                        PolynomialRealLowering.constant(BigFraction.ofLong(it)))
                }
                formulas += PolynomialRealFormula.Or(alternatives)
            } else domain.forEachHole { bound(value, PolynomialRelation.NE, it) }
        }
        for (id in 0 until numRealVars) {
            checkpoint()
            for ((endpoint, relation) in listOf(realLower[id] to PolynomialRelation.GE,
                realUpper[id] to PolynomialRelation.LE)) {
                if (endpoint.isNaN()) {
                    onDecline(EncoderDecline.UNSUPPORTED)
                    return null
                }
                if ((endpoint == Double.POSITIVE_INFINITY && relation == PolynomialRelation.GE) ||
                    (endpoint == Double.NEGATIVE_INFINITY && relation == PolynomialRelation.LE)
                ) formulas += PolynomialRealFormula.Constant(false)
                else if (endpoint.isFinite()) formulas += PolynomialRealFormula.Atom(real(id), relation,
                    PolynomialRealLowering.constant(requireNotNull(BigFraction.ofDouble(endpoint))))
            }
        }
        val integers = (0 until numIntVars).associate {
            checkpoint()
            "i$it" to it
        }
        val reals = (0 until numRealVars).associate {
            checkpoint()
            "r$it" to (numIntVars + it)
        }
        val booleans = (0 until numBoolVars).associate {
            checkpoint()
            "b$it" to it
        }
        val formula = lowering.formula(formulas)
        checkpoint()
        val native = NonlinearIntegerProblem(formula, lowering.realCount, numBoolVars,
            integers, reals, booleans, original.semantics)
        checkpoint()
        return FactorPolynomialProjection(original, this, native, projections, attributions, ownership)
    } catch (_: PolynomialSourceInterrupted) {
        onFailure(EncoderFailure(EncodingTarget.POLYNOMIAL, EncoderDecline.RESOURCE, activeFactor))
        onDecline(EncoderDecline.RESOURCE)
        return null
    }
}

internal fun FactorPolynomialProjection.prepareExecutionPlan(): PreparedSourcePlan {
    val ownership = finiteOwnership
    val arithmetic = ExecutionComponentDescriptor(
        "polynomial-integer",
        ComponentResponsibility.COMPLETION,
        attributions.map { it.sourceFactorIndex },
        ownership?.theoryIntVars?.toList() ?: (0 until model.numIntVars).toList(),
        (model.numIntVars until formula.numericCount).toList(),
        (0 until model.numBoolVars).toList(),
        if (ownership == null) nonlinearIntegerExecutionContract()
        else finiteNonlinearIntegerExecutionContract(refutesSource),
    )
    val components = if (ownership == null) listOf(arithmetic) else {
        val integers = ownership.cpIntVars.toList()
        val finite = ExecutionComponentDescriptor(
            "finite",
            ComponentResponsibility.FINITE,
            finiteFactors,
            integers,
            emptyList(),
            (0 until model.numBoolVars).toList(),
            model.finiteComponentContract(finiteFactors, integers, ownership,
                sourceCompletion = true, exactNeighborhood = false, projections = projections,
                authorities = if (refutesSource) completeAuthority else witnessAuthority),
        )
        listOf(finite, arithmetic)
    }
    return PreparedSourcePlan(
        original, PreparedExecution.NonlinearInteger(formula, this), components,
        model.numBoolVars, model.numIntVars, model.numRealVars,
        integerCoordinates = formula.integerCoordinates,
        realCoordinates = formula.realCoordinates,
        operations = if (ownership != null) setOf(ExecutionOperation.SATISFY) else sourceOperations,
        factorProjections = projections,
    )
}

private fun finiteNonlinearIntegerExecutionContract(refutesSource: Boolean): ComponentExecutionContract {
    val complete = ComponentStrategySupport(
        ProcedureCompleteness.WITNESS_SEARCH,
        if (refutesSource) witnessAuthority + ExecutionClaimAuthority.REFUTATION else witnessAuthority,
    )
    val unavailable = ComponentStrategySupport(
        ProcedureCompleteness.UNAVAILABLE,
        emptySet(),
        listOf(ExecutionDecline(ExecutionDeclineCause.SOURCE_COMPLETION, "polynomial-integer")),
    )
    return ComponentExecutionContract(complete, unavailable)
}
