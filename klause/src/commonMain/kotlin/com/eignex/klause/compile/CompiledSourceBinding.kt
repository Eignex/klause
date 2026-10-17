package com.eignex.klause.compile

import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.lowering.FloatRepresentation
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.klause.solver.pipeline.FloatGridConsumerPurpose
import com.eignex.klause.solver.pipeline.PreparedSourceInvocation
import com.eignex.klause.solver.pipeline.SourceAttachment
import com.eignex.klause.solver.pipeline.SourceSetup
import com.eignex.klause.solver.pipeline.prepareSelectedFloatGrid
import com.eignex.klause.solver.pipeline.preservesOriginalCompleteArm
import com.eignex.klause.solver.projection.FloatGridColumn
import com.eignex.klause.solver.projection.FloatGridDefinition
import com.eignex.klause.solver.projection.FloatGridPreparation
import com.eignex.klause.solver.projection.FloatGridRequest
import com.eignex.klause.solver.projection.FloatGridVariable
import com.eignex.klause.solver.projection.prepareFloatGrid
import com.eignex.klause.solver.validation.verifyOpenWitness
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

internal class CompiledSourceBinding(
    val originalProblem: Problem,
    integerNames: Map<String, Int>,
    realNames: Map<String, Int>,
    variables: List<FloatGridVariable> = emptyList(),
    representations: Map<String, FloatRepresentation> = emptyMap(),
    truncatedRows: Set<Int> = emptySet(),
) {
    val integerNames = integerNames.toMap()
    val realNames = realNames.toMap()
    private val variables = variables.toList()
    private val legacyRepresentations = representations.toMap()
    private val truncatedRows = truncatedRows.toSet()
    private val legacyDefinition: Lazy<FloatGridDefinition?> = lazy { prepareLegacy() }
    private val legacyColumns by lazy { legacyDefinition.value?.columns()?.associateBy { it.source.index }.orEmpty() }
    val legacyProblem: Problem get() = legacyDefinition.value?.target ?: originalProblem

    fun attachment(): SourceAttachment = SourceAttachment(originalProblem, emptyList(), null, variables)

    fun selectView(invocation: PreparedSourceInvocation) {
        require(invocation.source.originalSource === originalProblem)
        if (SourceSetup.preservesOriginalCompleteArm(invocation)) return
        val selected = selectedVariables(invocation.cancellation) ?: return
        if (selected.isNotEmpty()) SourceSetup.prepareSelectedFloatGrid(invocation, selected,
            FloatGridConsumerPurpose.AUTOMATIC_FINITE_COMPATIBILITY, truncatedRows)
    }

    fun floatRepresentations(): Map<String, FloatRepresentation> =
        if (originalProblem.semantics.effective.floats == FloatSemantics.Continuous) {
            legacyRepresentations.mapValues { FloatRepresentation(it.value.component) }
        } else legacyRepresentations.toMap()

    fun legacyColumn(name: String): FloatGridColumn? {
        val source = realNames[name] ?: return null
        return legacyColumns[source]
    }

    fun legacyIntegerNames(): Map<String, Int> = integerNames + realNames.mapNotNull { (name, _) ->
        legacyColumn(name)?.let { name to it.selector().coordinate.variable }
    }.toMap()

    fun legacyRealNames(): Map<String, Int> {
        val definition = legacyDefinition.value ?: return realNames.toMap()
        return realNames.mapNotNull { (name, source) ->
            definition.retainedReal(source).takeIf { it >= 0 }?.let { name to it }
        }.toMap()
    }

    fun isOriginal(sample: Sample): Boolean {
        val frame = sample.coordinateFrame
        if (frame === originalProblem) return true
        require(frame == null || (legacyDefinition.isInitialized() && frame === legacyDefinition.value?.target)) {
            "assignment belongs to a different compiled coordinate frame"
        }
        return false
    }

    fun bindOriginalSample(sample: Sample, cancellation: Cancellation): Sample? {
        val frame = sample.coordinateFrame
        require(frame == null || frame === originalProblem) { "source assignment retains a foreign coordinate frame" }
        if (cancellation()) return null
        if (verifyOpenWitness(originalProblem, sample, cancellation) !is Verification.Accepted) return null
        if (cancellation()) return null
        return sample.withCoordinateFrame(originalProblem)
    }

    fun paired(objective: LinearObjective): LinearObjective {
        val problem = legacyProblem
        val integers = MutableList<QuadraticVariable?>(problem.numIntVars) { id ->
            if (id < originalProblem.numIntVars) QuadraticVariable.Int(SourceIntId(id)) else null
        }
        legacyDefinition.value?.columns()?.forEach {
            integers[it.coordinate.variable] = QuadraticVariable.Real(it.source)
        }
        val reals = MutableList<QuadraticVariable?>(problem.numRealVars) { null }
        for (id in 0 until originalProblem.numRealVars) {
            val target = legacyDefinition.value?.retainedReal(id) ?: id
            if (target >= 0) reals[target] = QuadraticVariable.Real(SourceRealId(id))
        }
        val booleans = List<QuadraticVariable?>(problem.numBoolVars) { id ->
            if (id < originalProblem.numBoolVars) QuadraticVariable.Bool(SourceBoolId(id)) else null
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

    private fun prepareLegacy(): FloatGridDefinition? {
        val selected = selectedVariables(Cancellation.Never) ?: error("schema finite view preparation interrupted")
        if (selected.isEmpty()) return null
        val request = FloatGridRequest(originalProblem, selected, truncatedRows = truncatedRows)
        return when (val prepared = prepareFloatGrid(request, Cancellation.Never)) {
            is FloatGridPreparation.Prepared -> prepared.mapping.definition()
            is FloatGridPreparation.Declined -> error("schema finite view declined: ${prepared.cause}")
        }
    }

    private fun selectedVariables(cancellation: Cancellation): List<FloatGridVariable>? {
        if (cancellation()) return null
        if (originalProblem.semantics.effective.floats !is FloatSemantics.Grid) return emptyList()
        val selected = HashSet<Int>()
        for ((name, id) in realNames) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (legacyRepresentations[name]?.approximate == true) selected += id
        }
        if (selected.isEmpty()) return emptyList()
        val incident = HashMap<Int, MutableList<Int>>()
        for ((index, factor) in originalProblem.factors.withIndex()) {
            cancellation.charge(1)
            if (cancellation()) return null
            for (id in factor.variables.reals) {
                cancellation.charge(1)
                if (cancellation()) return null
                incident.getOrPut(id) { ArrayList() } += index
            }
        }
        val pending = ArrayDeque<Int>()
        pending.addAll(selected)
        val visited = HashSet<Int>()
        while (pending.isNotEmpty()) {
            for (factor in incident[pending.removeFirst()].orEmpty()) {
                cancellation.charge(1)
                if (cancellation()) return null
                if (!visited.add(factor)) continue
                for (id in originalProblem.factors[factor].variables.reals) {
                    cancellation.charge(1)
                    if (cancellation()) return null
                    if (selected.add(id)) pending += id
                }
            }
        }
        val result = ArrayList<FloatGridVariable>()
        for (variable in variables) {
            cancellation.charge(1)
            if (cancellation()) return null
            if (variable.source.index in selected) result += variable
        }
        return result
    }
}
