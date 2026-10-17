package com.eignex.klause.compile

import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.SourceObjectivePairing
import com.eignex.klause.util.unaryMinus

internal class CompiledObjectivePairing private constructor(
    override val owner: Any,
    private val booleans: List<QuadraticVariable?>,
    private val integers: List<QuadraticVariable?>,
    private val reals: List<QuadraticVariable?>,
    private val booleanWeights: List<QuadraticCoefficient?>,
    private val integerWeights: List<QuadraticCoefficient?>,
    private val realWeights: List<QuadraticCoefficient?>,
    private val constant: QuadraticCoefficient,
    private val native: LinearObjective,
) : SourceObjectivePairing {
    override fun original(objective: LinearObjective): QuadraticObjectiveSpec {
        require(objective.sourcePairing === this) { "objective does not retain its compiled pairing" }
        requireNative(objective)
        val terms = ArrayList<QuadraticObjectiveTerm>()
        fun append(coordinates: List<QuadraticVariable?>, weights: List<QuadraticCoefficient?>) {
            for ((id, captured) in weights.withIndex()) {
                val weight = requireNotNull(captured) { "compiled objective contains a nonfinite coefficient" }
                if (weight == QuadraticCoefficient.ZERO) continue
                val coordinate = requireNotNull(coordinates.getOrNull(id)) {
                    "compiled objective uses an unpaired coordinate"
                }
                terms += QuadraticObjectiveTerm(weight, coordinate)
            }
        }
        append(booleans, booleanWeights)
        append(integers, integerWeights)
        append(reals, realWeights)
        return QuadraticObjectiveSpec(terms, constant)
    }

    override fun copied(previous: LinearObjective, replacement: LinearObjective): SourceObjectivePairing {
        requireNative(previous)
        return CompiledObjectivePairing(
            owner, booleans, integers, reals,
            copyWeights(previous.boolWeightValues, replacement.boolWeightValues, booleanWeights),
            copyWeights(previous.intCoefficientValues, replacement.intCoefficientValues, integerWeights),
            replacement.realCoefficientValues.mapIndexed { id, value ->
                if (id in previous.realCoefficientValues.indices &&
                    value.toBits() == previous.realCoefficientValues[id].toBits()
                ) realWeights[id] else coefficient(value)
            },
            if (replacement.constant == previous.constant) constant
            else QuadraticCoefficient.ofLong(replacement.constant),
            snapshot(replacement),
        )
    }

    override fun negated(): SourceObjectivePairing =
        CompiledObjectivePairing(owner, booleans, integers, reals,
            booleanWeights.map { it?.negated() }, integerWeights.map { it?.negated() },
            realWeights.map { it?.negated() }, constant.negated(), native.negated())

    private fun requireNative(objective: LinearObjective) {
        require(objective.constant == native.constant &&
            objective.boolWeightValues.contentEquals(native.boolWeightValues) &&
            objective.intCoefficientValues.contentEquals(native.intCoefficientValues) &&
            objective.realCoefficientValues.contentEquals(native.realCoefficientValues)) {
            "objective coefficients do not retain their compiled pairing"
        }
    }

    companion object {
        fun capture(
            owner: Any,
            booleans: List<QuadraticVariable?>,
            integers: List<QuadraticVariable?>,
            reals: List<QuadraticVariable?>,
            objective: LinearObjective,
        ): CompiledObjectivePairing = CompiledObjectivePairing(
            owner, booleans.toList(), integers.toList(), reals.toList(),
            objective.boolWeightValues.map { QuadraticCoefficient.ofLong(it) },
            objective.intCoefficientValues.map { QuadraticCoefficient.ofLong(it) },
            objective.realCoefficientValues.map(::coefficient), QuadraticCoefficient.ofLong(objective.constant),
            snapshot(objective),
        )
    }
}

private fun copyWeights(
    previous: LongArray,
    replacement: LongArray,
    captured: List<QuadraticCoefficient?>,
): List<QuadraticCoefficient?> = replacement.mapIndexed { id, value ->
    if (id in previous.indices && value == previous[id]) captured[id] else QuadraticCoefficient.ofLong(value)
}

private fun coefficient(value: Double): QuadraticCoefficient? = BigFraction.ofDouble(value)?.let {
    QuadraticCoefficient.of(it.num, it.den)
}

private fun QuadraticCoefficient.negated(): QuadraticCoefficient = QuadraticCoefficient.of(-numerator, denominator)

private fun snapshot(objective: LinearObjective): LinearObjective = LinearObjective(
    objective.boolWeightValues, objective.intCoefficientValues, objective.constant, objective.realCoefficientValues,
)
