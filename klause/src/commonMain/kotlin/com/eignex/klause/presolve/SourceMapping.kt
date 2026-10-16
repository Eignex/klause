package com.eignex.klause.presolve

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceTransformation
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation

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
    chain: List<SourceMapping> = emptyList(),
    val justification: SourceTransformation? = null,
    liftDescribedByRebuild: Boolean = false,
    lift: ((Sample) -> Sample)? = null,
) {
    private val reconstruct: (Sample) -> Sample = lift ?: rebuild.asSampleLift() ?: { it }
    internal val opaqueReconstruction: Boolean = lift != null && !liftDescribedByRebuild
    private val capturedChain = chain.toList()
    val stages: List<SourceMapping> get() = capturedChain.ifEmpty { listOf(this) }.toList()

    val capturedJustifications: List<SourceTransformation> get() = stages.mapNotNull { it.justification }

    fun reconstructFrom(model: Problem, sample: Sample): Sample {
        require(model === target) { "reconstruction belongs to a different reduced model" }
        require(sample.numBoolVars == target.numBoolVars && sample.numIntVars == target.numIntVars) {
            "reconstruction needs a complete reduced assignment"
        }
        val lifted = reconstruct(sample)
        return if (source === target) lifted else lifted.copy().also { it.witnessCertificate = null }
    }

    fun requireObjectivePreserved(sourceObjective: LinearObjective, targetObjective: LinearObjective, sample: Sample) {
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

internal fun SourceMapping.retainedCoordinates(cancellation: Cancellation = Cancellation.Never): VarRemap? {
    if (cancellation()) return null
    val captured = stages
    for (stage in captured) {
        cancellation.charge(1)
        if (cancellation() || stage.opaqueReconstruction ||
            stage.target.numBoolVars < stage.source.numBoolVars ||
            stage.target.numIntVars < stage.source.numIntVars ||
            stage.target.numRealVars != stage.source.numRealVars
        ) {
            return null
        }
    }
    val bools = IntArray(source.numBoolVars)
    val ints = IntArray(source.numIntVars)
    for (id in bools.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        bools[id] = id
    }
    for (id in ints.indices) {
        cancellation.charge(1)
        if (cancellation()) return null
        ints[id] = id
    }
    for (stage in captured) {
        // Presolve preserves column slots; reconstructed columns are not direct execution coordinates.
        for (step in stage.rebuild.steps) {
            cancellation.charge(1)
            if (cancellation()) return null
            when (step) {
                is RebuildStep.CopyLiteral -> if (step.variable in bools.indices) bools[step.variable] = -1

                is RebuildStep.SatisfyClauses -> if (step.variable in bools.indices) bools[step.variable] = -1

                is RebuildStep.RepairClause -> Lit.variable(step.literal).let {
                    if (it in bools.indices) bools[it] = -1
                }

                is RebuildStep.AffineValue -> if (step.variable in ints.indices) ints[step.variable] = -1

                is RebuildStep.QuotientValue -> if (step.variable in ints.indices) ints[step.variable] = -1
            }
        }
    }
    return VarRemap(bools, ints)
}

internal fun List<PresolvePass>.transformationGuarantees(): TransformationGuarantees =
    fold(TransformationGuarantees.IDENTITY) { acc, pass -> acc.then(pass.guarantees) }
