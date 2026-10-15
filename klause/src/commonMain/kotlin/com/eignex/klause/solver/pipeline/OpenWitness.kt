package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong
import com.eignex.klause.util.parseBigInt
import com.eignex.klause.util.toLongExact

internal fun OpenTheoryAssignment.toSample(model: Problem, invocation: EngineInvocation? = null): Sample {
    require(invocation == null || ((invocation.sourceModel.sameModel(ModelIdentity.of(model)) ||
        invocation.preparedPlan.model === model) && invocation.model.sameModel(ModelIdentity.of(model)))) {
        "theory witness invocation has a different source"
    }
    val ints = List(model.numIntVars) { parseBigInt(intValue(it)) }
    val reals = List(model.numRealVars) { TheoryCompletion.parseRational(realValue(it)) }
    return Sample(
        bools = BooleanArray(model.numBoolVars) { boolValue(it) },
        ints = if (ints.all { it.fitsLong() }) LongArray(ints.size) { ints[it].toLongExact() } else LongArray(0),
        reals = DoubleArray(reals.size) { reals[it].toDouble() },
        exactReals = reals,
        exactInts = ints,
    ).also { sample ->
        sample.witnessCertificate = invocation?.let { binding ->
            EvidenceCertificate.published(CompletedEnginePublication.Witness(binding, sample,
                binding.executionObjective?.evaluateExact(sample)?.toDouble()))
        }
    }
}

internal fun refuteOpenWitness(model: Problem, sample: Sample): String? =
    com.eignex.klause.solver.validation.refuteOpenWitness(model, sample)

internal fun verifyOpenWitness(
    model: Problem, sample: Sample, cancellation: Cancellation = Cancellation.Never,
): Verification<Sample, Unit> = com.eignex.klause.solver.validation.verifyOpenWitness(model, sample, cancellation)
