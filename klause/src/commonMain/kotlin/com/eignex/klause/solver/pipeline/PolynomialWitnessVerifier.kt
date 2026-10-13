package com.eignex.klause.solver.pipeline

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelEvidence
import com.eignex.klause.solver.incumbent.ModelEvidenceVerifier
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation

internal fun polynomialWitnessVerifier(
    binding: EngineInvocation,
    cancellation: Cancellation,
): CandidateVerifier<Sample, Double?> {
    val verifier = ModelEvidenceVerifier<Sample, Double?>(binding.model, CandidateVerifier.trusting())
    return CandidateVerifier { candidate ->
        val certificate = candidate.assignment.witnessCertificate
        if (certificate?.publication !is CompletedEnginePublication.Witness) {
            return@CandidateVerifier Verification.Indeterminate("polynomial witness has no native publication")
        }
        when (val checked = verifier.verify(ModelEvidence.Witness(binding.model, candidate, certificate),
            binding.scope, binding, cancellation)) {
            is Verification.Accepted -> Verification.Accepted(candidate)
            is Verification.Rejected -> checked
            is Verification.Indeterminate -> checked
        }
    }
}
