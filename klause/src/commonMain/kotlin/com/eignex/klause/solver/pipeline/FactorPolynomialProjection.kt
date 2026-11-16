package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.ir.Problem
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
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.bigIntOf

internal class FactorPolynomialProjection(
    val original: Problem,
    val model: Problem,
    val formula: NonlinearIntegerProblem,
    val projections: PreparedFactorProjections,
    attributions: List<EncodingAttribution>
) {
    val attributions = attributions.toList()

    init {
        projections.requireSource(model)
        require(
            original.numIntVars == model.numIntVars && original.numRealVars == model.numRealVars &&
                original.numBoolVars == model.numBoolVars
        )
        require(
            original.numFactors == model.numFactors &&
                model.factors.indices.all { original.factors[it] === model.factors[it] }
        )
        require(this.attributions.map { it.sourceFactorIndex } == model.factors.indices.toList())
        require(formula.integerCoordinates == (0 until model.numIntVars).toList())
        require(formula.realCoordinates == (model.numIntVars until model.numIntVars + model.numRealVars).toList())
        require(formula.boolCount == model.numBoolVars && formula.semantics == original.semantics)
    }
}

internal fun Problem.integerPolynomialProjection(
    original: Problem,
    projections: PreparedFactorProjections,
    cancellation: ExecutionControl,
    onDecline: (EncoderDecline) -> Unit
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
        val request = projections.requestEncoders(
            EncodingTarget.POLYNOMIAL,
            factors.indices.toList(),
            fixedIntegers = fixedIntegers,
            cancellation = stop,
            onDecline = onDecline
        ) ?: return null
        val lowering = PolynomialRealLowering(numIntVars + numRealVars)
        val formulas = ArrayList<PolynomialRealFormula>()
        val attributions = ArrayList<EncodingAttribution>()
        fun integer(id: Int) = PolynomialExpression.Variable(SourceRealId(id))
        fun real(id: Int) = PolynomialExpression.Variable(SourceRealId(numIntVars + id))
        for ((position, index) in request.indices.withIndex()) {
            checkpoint()
            val factor = factors[index]
            val encoding = PolynomialEncodingContext(index, factor, lowering, request.cancellation,
                integerCoordinate = { it }, realCoordinate = {numIntVars + it
                },
                fixedIntegers = request.fixedIntegers, fixedBooleans = request.fixedBooleans)
            val decline = request.encoders[position].install(factor, encoding)
            if (decline != null || encoding.formulas.isEmpty()) {
                onDecline(decline ?: EncoderDecline.UNSUPPORTED)
                return null
            }
            formulas += encoding.formulas
            attributions += encoding.attribution(
            )
        }
        fun bound(expression: PolynomialExpression, relation: PolynomialRelation, value: Long) {
            checkpoint()
            formulas += PolynomialRealFormula.Atom(
                expression,
                relation,
                PolynomialRealLowering.constant(BigFraction.ofLong(value))
            )
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
                    alternatives += PolynomialRealFormula.Atom(
                        value,
                        PolynomialRelation.EQ,
                        PolynomialRealLowering.constant(BigFraction.ofLong(it))
                    )
                }
                formulas += PolynomialRealFormula.Or(alternatives)
            } else {
                domain.forEachHole { bound(value, PolynomialRelation.NE, it) }
            }
        }
        for (id in 0 until numRealVars) {
            checkpoint()
            for ((endpoint, relation) in listOf(
                realLower[id] to PolynomialRelation.GE,
                realUpper[id] to PolynomialRelation.LE
            )) {
                if (endpoint.isNaN()) {
                    onDecline(EncoderDecline.UNSUPPORTED)
                    return null
                }
                if ((endpoint == Double.POSITIVE_INFINITY && relation == PolynomialRelation.GE) ||
                    (endpoint == Double.NEGATIVE_INFINITY && relation == PolynomialRelation.LE)
                ) {
                    formulas += PolynomialRealFormula.Constant(false)
                } else if (endpoint.isFinite()) {
                    formulas += PolynomialRealFormula.Atom(
                        real(id),
                        relation,
                        PolynomialRealLowering.constant(requireNotNull(BigFraction.ofDouble(endpoint)))
                    )
                }
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
        val native = NonlinearIntegerProblem(
            formula,
            lowering.realCount,
            numBoolVars,
            integers,
            reals,
            booleans,
            original.semantics
        )
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
    listOf(
        ExecutionComponentDescriptor(
            "polynomial-integer",
            ComponentResponsibility.COMPLETION,
            model.factors.indices.toList(),
            (0 until model.numIntVars).toList(),
            (model.numIntVars until formula.numericCount).toList(),
            (0 until model.numBoolVars).toList(),
            nonlinearIntegerExecutionContract()
        )
    ),
    model.numBoolVars,
    model.numIntVars,
    model.numRealVars,
    integerCoordinates = formula.integerCoordinates,
    realCoordinates = formula.realCoordinates,
    factorProjections = projections
)
