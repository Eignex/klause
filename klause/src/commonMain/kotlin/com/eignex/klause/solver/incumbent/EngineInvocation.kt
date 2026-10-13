package com.eignex.klause.solver.incumbent

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.DeducedRestrictions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.checkWitness
import com.eignex.klause.solver.evidence.sourceCondition
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.PreparedSourcePlan
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.toLongExact

internal class EngineInvocation(
    val preparedPlan: PreparedSourcePlan,
    val model: ModelIdentity,
    val executionObjective: LinearObjective? = preparedPlan.objective,
    sourceAssumptions: Assumptions = Assumptions.None,
    executionAssumptions: Assumptions = sourceAssumptions,
    val scope: SourceScope? = null,
) {
    private val sourcePins = snapshot(sourceAssumptions)
    private val executionPins = snapshot(executionAssumptions)
    val sourceCondition: SourceCondition = sourcePins.sourceCondition()
    private val sourceWitnessCondition: SourceCondition = executionCondition(sourcePins)
    val executionCondition: SourceCondition = executionCondition(executionPins)
    val sourceObjective: LinearObjective? get() = preparedPlan.objective
    val sense: ObjectiveSense get() = if (preparedPlan.maximize) ObjectiveSense.MAXIMIZE else ObjectiveSense.MINIMIZE
    val sourceModel: ModelIdentity = ModelIdentity.of(preparedPlan.originalSource, sourceObjective)
    val hasUncapturedSourceRestrictions: Boolean get() = !sourcePins.deductions.isEmpty
    val assumptions: Assumptions get() = snapshot(executionPins)

    init {
        require(preparedPlan.quadraticObjective == null) { "ordinary invocation cannot replace a quadratic objective" }
        require(sourcePins.bools.keys.all { it in 0 until preparedPlan.sourceBoolCount } &&
            sourcePins.ints.keys.all { it in 0 until preparedPlan.sourceIntCount }) { "source pin is undeclared" }
        require(model.matches(model.forObjective(executionObjective))) { "execution objective differs from its model" }
        require(scope == null || scope.condition == sourceCondition) { "capture has a different source condition" }
        require(scope == null || (scope.objective == null) == (sourceObjective == null)) {
            "capture has a different source objective"
        }
        require(scope?.objective == null || scope.objective.sense == sense) {
            "capture has a different source direction"
        }
    }

    fun matches(other: EngineInvocation): Boolean = preparedPlan === other.preparedPlan &&
        model.matches(other.model) && executionObjective === other.executionObjective &&
        sourcePins == other.sourcePins && executionPins == other.executionPins && scope == other.scope

    fun checkWitness(sample: Sample, cancellation: Cancellation): SourceEvidenceCheck {
        val source = checkRestrictions(sample, sourcePins, sourceWitnessCondition, cancellation)
        if (source != SourceEvidenceCheck.Checked || sourcePins == executionPins) return source
        return checkRestrictions(sample, executionPins, executionCondition, cancellation)
    }

    private fun checkRestrictions(
        sample: Sample, pins: Assumptions, condition: SourceCondition, cancellation: Cancellation,
    ): SourceEvidenceCheck {
        val checked = condition.checkWitness(sample, cancellation)
        if (checked != SourceEvidenceCheck.Checked) return checked
        val deductions = pins.deductions
        for (i in deductions.intHoleVarIds.indices) {
            if (cancellation()) return SourceEvidenceCheck.Unavailable("runtime condition check cancelled")
            val id = deductions.intHoleVarIds[i]
            if (id !in 0 until sample.numIntVars || sample.exactIntValue(id) == bigIntOf(deductions.intHoleValues[i])) {
                return SourceEvidenceCheck.Rejected("witness violates a runtime integer hole")
            }
        }
        for (i in deductions.intSetKeys.indices) {
            if (cancellation()) return SourceEvidenceCheck.Unavailable("runtime condition check cancelled")
            val id = deductions.intSetKeys[i]
            if (id !in 0 until sample.numIntVars) return SourceEvidenceCheck.Rejected("runtime integer is undeclared")
            val value = sample.exactIntValue(id)
            if (!value.fitsLong() || value.toLongExact() !in deductions.intSetSurvivorsAt(i)) {
                return SourceEvidenceCheck.Rejected("witness violates a runtime integer set")
            }
        }
        return SourceEvidenceCheck.Checked
    }

    private fun executionCondition(value: Assumptions): SourceCondition {
        val explicit = value.sourceCondition()
        val lower = explicit.lower.toMutableMap()
        val upper = explicit.upper.toMutableMap()
        value.deductions.forEachIntMin { id, bound ->
            val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, id)
            val exact = BigFraction.ofLong(bound)
            lower[coordinate] = lower[coordinate]?.let { if (it > exact) it else exact } ?: exact
        }
        value.deductions.forEachIntMax { id, bound ->
            val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, id)
            val exact = BigFraction.ofLong(bound)
            upper[coordinate] = upper[coordinate]?.let { if (it < exact) it else exact } ?: exact
        }
        return SourceCondition(explicit.bools, lower, upper)
    }

    private fun snapshot(value: Assumptions): Assumptions {
        val d = value.deductions
        return Assumptions(
            value.boolKeys.copyOf(), value.boolValues.copyOf(), value.intKeys.copyOf(), value.intValues.copyOf(),
            DeducedRestrictions(
                d.intMinKeys.copyOf(), d.intMinValues.copyOf(), d.intMaxKeys.copyOf(), d.intMaxValues.copyOf(),
                d.intHoleVarIds.copyOf(), d.intHoleValues.copyOf(), d.intSetKeys.copyOf(),
                d.intSetOffsets.copyOf(), d.intSetValues.copyOf(),
            ),
        )
    }
}
