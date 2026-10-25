package com.eignex.klause.solver.pipeline

import com.eignex.klause.presolve.retainedCoordinates
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.validation.finiteWitnessVerifier
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.fitsLong

internal class ObjectiveCoordinateView private constructor(
    val problem: BakedProblem,
    val objective: LinearObjective,
    val hints: PreparedSourceHints?,
    val canonicalModel: ModelIdentity,
    val proposal: (Sample) -> MinimizeResult.BestFound?,
) {
    companion object {
        fun create(
            canonical: FinitePipelinePreparation,
            coordinates: FinitePipelinePreparation,
            hints: PreparedSourceHints?,
            cancellation: Cancellation,
        ): ObjectiveCoordinateView? {
            val mapping = canonical.sourceMapping ?: return null
            val privateMapping = coordinates.sourceMapping ?: return null
            if (mapping.source !== privateMapping.source ||
                !mapping.guarantees.reconstructability || !privateMapping.guarantees.reconstructability ||
                mapping.retainedCoordinates(cancellation) == null ||
                privateMapping.retainedCoordinates(cancellation) == null
            ) return null
            val target = canonical.problem as? BakedProblem ?: return null
            val privateProblem = coordinates.problem as? BakedProblem ?: return null
            val targetObjective = canonical.objective ?: return null
            val privateObjective = coordinates.objective ?: return null
            // Presolve retains column slots. Only equal, discrete namespaces can share reconstructed proposals.
            if (listOf(mapping.source, target, privateProblem).any {
                it.numBoolVars != target.numBoolVars || it.numIntVars != target.numIntVars || it.numRealVars != 0
            }) return null
            val verifier = finiteWitnessVerifier(target, targetObjective, cancellation)
            return ObjectiveCoordinateView(
                privateProblem, privateObjective, hints, ModelIdentity.of(target, targetObjective),
            ) { point ->
                if (cancellation() || point.numBoolVars != privateProblem.numBoolVars ||
                    point.numIntVars != privateProblem.numIntVars || point.numRealVars != 0
                ) return@ObjectiveCoordinateView null
                val lifted = coordinates.reconstruct(point)
                if (lifted.exactInts?.any { !it.fitsLong() } == true) return@ObjectiveCoordinateView null
                // Drop private certificates and frames; the canonical verifier establishes fresh witness authority.
                val sample = Sample(lifted.bools, lifted.ints)
                val value = targetObjective.evaluate(sample)
                if (verifier.verify(Candidate(sample, value)) is Verification.Accepted) {
                    MinimizeResult.BestFound(sample, value, TerminationReason.SearchExhausted)
                } else null
            }
        }
    }
}
