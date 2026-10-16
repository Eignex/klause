package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.PolynomialRealLowering
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.encodingPreparationScope
import com.eignex.klause.theory.EncoderDecline
import com.eignex.klause.theory.EncodingAttribution
import com.eignex.klause.theory.EncodingTarget
import com.eignex.klause.theory.TheoryEncoding
import com.eignex.klause.theory.TheoryRealId
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf

internal class FactorPolynomialProjection(
    val original: Problem,
    val model: Problem,
    val formula: NonlinearIntegerProblem,
    val projections: PreparedFactorProjections,
    attributions: List<EncodingAttribution>,
) {
    val attributions = attributions.toList()

    init {
        projections.requireSource(model)
        require(original.numIntVars == model.numIntVars && original.numRealVars == model.numRealVars &&
            original.numBoolVars == model.numBoolVars)
        require(original.numFactors == model.numFactors &&
            model.factors.indices.all { original.factors[it] === model.factors[it] })
        require(this.attributions.map { it.sourceFactorIndex } == model.factors.indices.toList())
        require(formula.integerCoordinates == (0 until model.numIntVars).toList())
        require(formula.realCoordinates == (model.numIntVars until model.numIntVars + model.numRealVars).toList())
        require(formula.boolCount == model.numBoolVars && formula.semantics == original.semantics)
    }
}

internal fun Problem.integerPolynomialProjection(
    original: Problem,
    projections: PreparedFactorProjections,
    cancellation: Cancellation,
    onDecline: (EncoderDecline) -> Unit,
): FactorPolynomialProjection? {
    projections.requireSource(this)
    val stop = cancellation.encodingPreparationScope()
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
        for (id in 0 until numIntVars) {
            checkpoint()
            if (intBounds.hasLower(id) && intBounds.hasUpper(id) && intBounds.lower(id) == intBounds.upper(id)) {
                fixedIntegers[SourceIntId(id)] = bigIntOf(intBounds.lower(id))
            }
        }
        // There is no finite peer: every original relation must belong to this selected scope.
        val request = projections.requestEncoders(EncodingTarget.POLYNOMIAL, factors.indices.toList(),
            fixedIntegers = fixedIntegers, cancellation = stop, onDecline = onDecline) ?: return null
        val lowering = PolynomialRealLowering(numIntVars + numRealVars)
        val formulas = ArrayList<PolynomialRealFormula>()
        val attributions = ArrayList<EncodingAttribution>()
        fun integer(id: Int) = PolynomialExpression.Variable(SourceRealId(id))
        fun real(id: Int) = PolynomialExpression.Variable(SourceRealId(numIntVars + id))
        for ((position, index) in request.indices.withIndex()) {
            checkpoint()
            val factor = factors[index]
            val emitted = ArrayList<PolynomialRealFormula>()
            val integerMapping = LinkedHashMap<SourceIntId, TheoryRealId>()
            val realMapping = LinkedHashMap<SourceRealId, TheoryRealId>()
            val auxiliaries = ArrayList<TheoryRealId>()
            val encoding = object : TheoryEncoding {
                override val target = EncodingTarget.POLYNOMIAL
                override val sourceFactorIndex = index
                override val cancellation = request.cancellation
                override fun fixedInteger(id: SourceIntId): BigInt? = request.fixedIntegers[id]
                override fun fixedBoolean(id: SourceBoolId): Boolean? = request.fixedBooleans[id]
                override fun linear(form: LinearForm): Unit = error("polynomial target requires a formula")
                private fun coordinates() =
                    (integerMapping.values + realMapping.values + auxiliaries).map { it.index }.toSet()
                override fun polynomial(formula: PolynomialRealFormula) {
                    validatePolynomialEncodingCoordinates(formula, null, coordinates(), factor.boolVars.toSet(),
                        cancellation)
                    emitted += formula
                }
                override fun integer(id: SourceIntId): PolynomialExpression {
                    require(id.index in factor.intVars)
                    integerMapping[id] = TheoryRealId(id.index)
                    return integer(id.index)
                }
                override fun real(id: SourceRealId): PolynomialExpression {
                    require(id.index in factor.variables.reals)
                    realMapping[id] = TheoryRealId(numIntVars + id.index)
                    return real(id.index)
                }
                override fun conditional(
                    condition: PolynomialRealFormula,
                    whenTrue: PolynomialExpression,
                    whenFalse: PolynomialExpression,
                ): PolynomialExpression {
                    val coordinates = coordinates()
                    val booleans = factor.boolVars.toSet()
                    validatePolynomialEncodingCoordinates(condition, null, coordinates, booleans, cancellation)
                    validatePolynomialEncodingCoordinates(null, whenTrue, coordinates, booleans, cancellation)
                    validatePolynomialEncodingCoordinates(null, whenFalse, coordinates, booleans, cancellation)
                    val before = lowering.realCount
                    return lowering.conditional(condition, whenTrue, whenFalse).also {
                        for (id in before until lowering.realCount) auxiliaries += TheoryRealId(id)
                    }
                }
            }
            val decline = request.encoders[position].install(factor, encoding)
            if (decline != null || emitted.isEmpty()) {
                onDecline(decline ?: EncoderDecline.UNSUPPORTED)
                return null
            }
            formulas += emitted
            attributions += EncodingAttribution(index, factor.boolVars.map(::SourceBoolId),
                factor.intVars.map(::SourceIntId), factor.variables.reals.map(::SourceRealId),
                integerMapping, realMapping, auxiliaries,
                request.fixedIntegers, request.fixedBooleans)
        }
        fun bound(expression: PolynomialExpression, relation: PolynomialRelation, value: Long) {
            checkpoint()
            formulas += PolynomialRealFormula.Atom(expression, relation,
                PolynomialRealLowering.constant(BigFraction.ofLong(value)))
        }
        for (id in 0 until numIntVars) {
            checkpoint()
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
        val integers = (0 until numIntVars).associate { checkpoint(); "i$it" to it }
        val reals = (0 until numRealVars).associate { checkpoint(); "r$it" to (numIntVars + it) }
        val booleans = (0 until numBoolVars).associate { checkpoint(); "b$it" to it }
        val formula = lowering.formula(formulas)
        checkpoint()
        val native = NonlinearIntegerProblem(formula, lowering.realCount, numBoolVars,
            integers, reals, booleans, original.semantics)
        checkpoint()
        return FactorPolynomialProjection(original, this, native, projections, attributions)
    } catch (_: PolynomialSourceInterrupted) {
        onDecline(EncoderDecline.RESOURCE)
        return null
    }
}

internal fun FactorPolynomialProjection.prepareExecutionPlan(): PreparedSourcePlan = PreparedSourcePlan(
    original,
    PreparedExecution.NonlinearInteger(formula, this),
    listOf(ExecutionComponentDescriptor(
        "polynomial-integer",
        ComponentResponsibility.COMPLETION,
        model.factors.indices.toList(),
        (0 until model.numIntVars).toList(),
        (model.numIntVars until formula.numericCount).toList(),
        (0 until model.numBoolVars).toList(),
        nonlinearIntegerExecutionContract(),
    )),
    model.numBoolVars,
    model.numIntVars,
    model.numRealVars,
    integerCoordinates = formula.integerCoordinates,
    realCoordinates = formula.realCoordinates,
    factorProjections = projections,
)
