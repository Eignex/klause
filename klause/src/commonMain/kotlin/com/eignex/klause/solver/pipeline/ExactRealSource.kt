package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.ExactRealEquality
import com.eignex.klause.ir.*
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.theory.*
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf

private class ExactRealSourceUnavailable : RuntimeException()

internal fun prepareExactRealSource(request: SourceSetupRequest, stop: Cancellation): SourceProblemRoute.NativeFormula {
    if (request.operation != ExecutionOperation.SATISFY) return SourceProblemRoute.NativeFormula(null,
        com.eignex.klause.solver.result.TerminationReason.Unsupported)
    val functions = request.source.problem.exactRealSource(request.providers, stop)
        ?: return SourceProblemRoute.NativeFormula(null, if (stop())
            com.eignex.klause.solver.result.TerminationReason.Cancelled
        else com.eignex.klause.solver.result.TerminationReason.Unsupported)
    return prepareNativeSource(request, AttachedSourceFormula.Functions(functions), stop)
}

internal fun Problem.exactRealSource(
    providers: com.eignex.klause.solver.FactorProjectionProviders,
    stop: Cancellation,
): TranscendentalProblem? {
    val source = this
    fun checkpoint() {
        stop.charge(1)
        if (stop()) throw ExactRealSourceUnavailable()
    }
    return try {
        val formulas = ArrayList<TranscendentalFormula>()
        fun real(id: Int) = TranscendentalExpression.Variable(SourceRealId(source.numIntVars + id))
        fun integer(id: Int) = TranscendentalExpression.Variable(SourceRealId(id))
        fun constant(value: BigFraction) = TranscendentalExpression.Constant(
            PolynomialExpression.Constant.of(value.num, value.den),
        )
        fun relation(value: TranscendentalExpression, op: PolynomialRelation, bound: BigFraction) {
            checkpoint()
            formulas += TranscendentalFormula.Atom(value, op, constant(bound))
        }
        val fixed = (0 until source.numIntVars).mapNotNull { id ->
            checkpoint()
            if (source.intBounds.hasLower(id) && source.intBounds.hasUpper(id) &&
                source.intBounds.lower(id) == source.intBounds.upper(id)
            ) SourceIntId(id) to bigIntOf(source.intBounds.lower(id)) else null
        }.toMap()
        val projections = providers.prepare(source)
        for ((index, factor) in source.factors.withIndex()) {
            checkpoint()
            if (factor is ExactRealEquality) {
                formulas += TranscendentalFormula.Atom(real(factor.id.index), PolynomialRelation.EQ,
                    TranscendentalExpression.ExactValue(factor.value))
                continue
            }
            val encoder = projections.requestEncoders(EncodingTarget.POLYNOMIAL, listOf(index),
                fixedIntegers = fixed, cancellation = stop)?.encoders?.single()
                ?: throw ExactRealSourceUnavailable()
            var emissions = 0
            val encoding = object : TheoryEncoding {
                override val target = EncodingTarget.POLYNOMIAL
                override val sourceFactorIndex = index
                override val cancellation = stop
                override fun fixedInteger(id: SourceIntId): BigInt? = fixed[id]
                override fun fixedBoolean(id: SourceBoolId): Boolean? = null
                override fun linear(form: LinearForm): Unit = throw ExactRealSourceUnavailable()
                override fun polynomial(formula: PolynomialRealFormula) {
                    formulas += formula.functions(::checkpoint)
                    emissions++
                }
                override fun integer(id: SourceIntId): PolynomialExpression {
                    require(id.index in factor.intVars)
                    return PolynomialExpression.Variable(SourceRealId(id.index))
                }
                override fun real(id: SourceRealId): PolynomialExpression {
                    require(id.index in factor.variables.reals)
                    return PolynomialExpression.Variable(SourceRealId(source.numIntVars + id.index))
                }
                override fun conditional(condition: PolynomialRealFormula, whenTrue: PolynomialExpression,
                    whenFalse: PolynomialExpression): PolynomialExpression = throw ExactRealSourceUnavailable()
            }
            if (encoder.install(factor, encoding) != null || emissions == 0) throw ExactRealSourceUnavailable()
        }
        for (id in 0 until source.numIntVars) {
            val bounds = source.intBounds
            if (bounds.hasLower(id)) relation(integer(id), PolynomialRelation.GE, BigFraction.ofLong(bounds.lower(id)))
            if (bounds.hasUpper(id)) relation(integer(id), PolynomialRelation.LE, BigFraction.ofLong(bounds.upper(id)))
            val domain = source.intDomainOrNull(id) ?: continue
            if (domain.holeCount != 0L) {
                val members = domain.spanOrNull(domain.holeCount) ?: throw ExactRealSourceUnavailable()
                formulas += TranscendentalFormula.Or(members.map {
                    checkpoint()
                    TranscendentalFormula.Atom(integer(id), PolynomialRelation.EQ, constant(BigFraction.ofLong(it)))
                })
            }
        }
        for (id in 0 until source.numRealVars) {
            val lower = source.realLower[id]
            val upper = source.realUpper[id]
            if (lower.isFinite()) relation(real(id), PolynomialRelation.GE, requireNotNull(BigFraction.ofDouble(lower)))
            if (upper.isFinite()) relation(real(id), PolynomialRelation.LE, requireNotNull(BigFraction.ofDouble(upper)))
            if (lower.isNaN() || upper.isNaN()) throw ExactRealSourceUnavailable()
            if (lower == Double.POSITIVE_INFINITY || upper == Double.NEGATIVE_INFINITY) {
                formulas += TranscendentalFormula.Constant(false)
            }
        }
        val functions = TranscendentalProblem(TranscendentalFormula.And(formulas),
            (0 until source.numIntVars).associate { "ints.$it" to it },
            (0 until source.numRealVars).associate { "reals.$it" to (source.numIntVars + it) },
            (0 until source.numBoolVars).associate { "bools.$it" to it }, semantics = source.semantics)
        functions
    } catch (_: ExactRealSourceUnavailable) {
        null
    }
}

private fun PolynomialExpression.functions(checkpoint: () -> Unit): TranscendentalExpression {
    checkpoint()
    return when (this) {
        is PolynomialExpression.Constant -> TranscendentalExpression.Constant(this)
        is PolynomialExpression.Variable -> TranscendentalExpression.Variable(id)
        is PolynomialExpression.Sum -> TranscendentalExpression.Sum(operands.map { it.functions(checkpoint) })
        is PolynomialExpression.Product -> TranscendentalExpression.Product(operands.map { it.functions(checkpoint) })
        is PolynomialExpression.Power -> TranscendentalExpression.Power(base.functions(checkpoint), exponent)
    }
}

private fun PolynomialRealFormula.functions(checkpoint: () -> Unit): TranscendentalFormula {
    checkpoint()
    return when (this) {
        is PolynomialRealFormula.Constant -> TranscendentalFormula.Constant(value)
        is PolynomialRealFormula.BooleanVariable -> TranscendentalFormula.BooleanVariable(id)
        is PolynomialRealFormula.Atom -> TranscendentalFormula.Atom(left.functions(checkpoint), relation,
            right.functions(checkpoint))
        is PolynomialRealFormula.Not -> TranscendentalFormula.Not(operand.functions(checkpoint))
        is PolynomialRealFormula.And -> TranscendentalFormula.And(operands.map { it.functions(checkpoint) })
        is PolynomialRealFormula.Or -> TranscendentalFormula.Or(operands.map { it.functions(checkpoint) })
        is PolynomialDistinct -> TranscendentalDistinct(operands.map { it.functions(checkpoint) })
        is PolynomialSelection -> throw ExactRealSourceUnavailable()
    }
}
