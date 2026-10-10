package com.eignex.klause.presolve

import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective

internal class ObjectiveAdjustment(
    val scale: BigFraction = BigFraction.ONE,
    val offset: BigFraction = BigFraction.ZERO,
) {
    init {
        require(scale.signum() > 0) { "objective adjustment must preserve minimization order" }
    }

    fun sourceValue(reduced: BigFraction): BigFraction = scale * reduced + offset

    fun then(next: ObjectiveAdjustment): ObjectiveAdjustment =
        ObjectiveAdjustment(scale * next.scale, scale * next.offset + offset)
}

internal class SourceMapping(
    val source: Problem,
    val target: Problem,
    val guarantees: TransformationGuarantees,
    val rebuild: SourceRebuilds = SourceRebuilds.NONE,
    val objectiveAdjustment: ObjectiveAdjustment = ObjectiveAdjustment(),
    private val chain: List<SourceMapping> = emptyList(),
    private val lift: (Sample) -> Sample = rebuild.asSampleLift() ?: { it },
) {
    val stages: List<SourceMapping> get() = chain.ifEmpty { listOf(this) }

    fun reconstructFrom(model: Problem, sample: Sample): Sample {
        require(model === target) { "reconstruction belongs to a different reduced model" }
        require(sample.bools.size == target.numBoolVars && sample.numIntVars == target.numIntVars) {
            "reconstruction needs a complete reduced assignment"
        }
        val lifted = lift(sample)
        return if (source === target) lifted else lifted.copy().also { it.witnessCertificate = null }
    }

    fun requireObjectivePreserved(
        sourceObjective: LinearObjective,
        targetObjective: LinearObjective,
        sample: Sample,
    ) {
        require(guarantees.objective && guarantees.reconstructability)
        val reconstructed = reconstructFrom(target, sample)
        val expected = objectiveAdjustment.sourceValue(targetObjective.evaluateExact(sample))
        require(sourceObjective.evaluateExact(reconstructed) == expected) {
            "transformation objective adjustment disagrees with source reconstruction"
        }
    }

    fun then(next: SourceMapping): SourceMapping {
        require(target === next.source) { "transformation chain has unrelated model revisions" }
        return SourceMapping(
            source,
            next.target,
            guarantees.then(next.guarantees),
            SourceRebuilds.compose(listOf(rebuild, next.rebuild)),
            objectiveAdjustment.then(next.objectiveAdjustment),
            stages + next.stages,
        ) { sample -> reconstructFrom(target, next.reconstructFrom(next.target, sample)) }
    }
}

internal fun List<PresolvePass>.transformationGuarantees(): TransformationGuarantees =
    fold(TransformationGuarantees.IDENTITY) { acc, pass -> acc.then(pass.guarantees) }
