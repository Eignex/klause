package com.eignex.klause.compile

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.FloatRepresentation
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.validation.verifyOpenWitness
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.fitsLong
import com.eignex.util.numbers.toLongExact

internal class CompiledSourceBinding(
    val originalProblem: Problem,
    integerNames: Map<String, Int>,
    realNames: Map<String, Int>,
    representations: Map<String, FloatRepresentation> = emptyMap(),
) {
    val integerNames = integerNames.toMap()
    val realNames = realNames.toMap()
    private val declaredRepresentations = representations.toMap()

    fun attachment(): SourceAttachment = SourceAttachment(originalProblem)

    fun floatRepresentations(): Map<String, FloatRepresentation> = declaredRepresentations.toMap()

    fun isOriginal(sample: Sample): Boolean {
        val frame = sample.coordinateFrame
        if (frame === originalProblem) return true
        require(frame == null) {
            "assignment belongs to a different compiled coordinate frame"
        }
        return false
    }

    fun bindOriginalSample(sample: Sample, cancellation: ExecutionControl): Sample? {
        val frame = sample.coordinateFrame
        require(frame == null || frame === originalProblem) { "source assignment retains a foreign coordinate frame" }
        if (cancellation()) return null
        if (verifyOpenWitness(originalProblem, sample, cancellation) !is Verification.Accepted) return null
        if (cancellation()) return null
        return sample.withCoordinateFrame(originalProblem)
    }

    fun paired(objective: LinearObjective): LinearObjective {
        val integers = List<QuadraticVariable?>(originalProblem.numIntVars) { QuadraticVariable.Int(SourceIntId(it)) }
        val reals = List<QuadraticVariable?>(originalProblem.numRealVars) { QuadraticVariable.Real(SourceRealId(it)) }
        val booleans = List<QuadraticVariable?>(originalProblem.numBoolVars) {
            QuadraticVariable.Bool(SourceBoolId(it))
        }
        return objective.paired(CompiledObjectivePairing.capture(this, booleans, integers, reals, objective))
    }

    fun originalObjective(objective: LinearObjective): QuadraticObjectiveSpec {
        val pairing = objective.sourcePairing
        if (pairing != null) {
            require(pairing.owner === this) { "objective belongs to a different compiled source" }
            return pairing.original(objective)
        }
        require(objective.boolWeightValues.size <= originalProblem.numBoolVars &&
            objective.intCoefficientValues.size <= originalProblem.numIntVars &&
            objective.realCoefficientValues.size <= originalProblem.numRealVars) {
            "unbound objective does not use original source coordinates"
        }
        return objective.toQuadraticObjectiveSpec()
    }

    fun openObjective(objective: LinearObjective): LinearObjective? {
        val original = originalObjective(objective)
        if (objective.sourcePairing == null) return objective
        val booleans = Array(originalProblem.numBoolVars) { BigFraction.ZERO }
        val integers = Array(originalProblem.numIntVars) { BigFraction.ZERO }
        val reals = Array(originalProblem.numRealVars) { BigFraction.ZERO }
        for (term in original.terms) {
            if (term.second != null) return null
            val coefficient = BigFraction.of(term.coefficient.numerator, term.coefficient.denominator)
            when (val variable = term.first) {
                is QuadraticVariable.Bool -> booleans[variable.id.index] += coefficient
                is QuadraticVariable.Int -> integers[variable.id.index] += coefficient
                is QuadraticVariable.Real -> reals[variable.id.index] += coefficient
            }
        }
        fun words(values: Array<BigFraction>): LongArray? {
            if (values.any { it.den != BIG_ONE || !it.num.fitsLong() }) return null
            return LongArray(values.size) { values[it].num.toLongExact() }
        }
        val boolWeights = words(booleans) ?: return null
        val intWeights = words(integers) ?: return null
        val realWeights = DoubleArray(reals.size) {
            val approximate = reals[it].toDouble()
            if (BigFraction.ofDouble(approximate) != reals[it]) return null
            approximate
        }
        val constant = original.constant
        if (constant.denominator != BIG_ONE || !constant.numerator.fitsLong()) return null
        return LinearObjective(boolWeights, intWeights, constant.numerator.toLongExact(), realWeights)
    }
}
