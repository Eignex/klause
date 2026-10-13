package com.eignex.klause.solver.pipeline

import com.eignex.klause.lp.optimization.withLinearAuthority
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation

internal fun SourceProofContext.linearWitnessVerifier(
    cancellation: Cancellation,
): CandidateVerifier<Sample, Double?> = CandidateVerifier { candidate ->
    val witness = SourceEvidence(scope, SourceClaim.Witness(candidate.assignment), SourceDerivation.SourceWitness)
    when (val checked = SourceEvidenceVerifier(withLinearAuthority()).verify(witness, cancellation)) {
        SourceEvidenceCheck.Checked -> Verification.Accepted(candidate)
        is SourceEvidenceCheck.Rejected -> Verification.Rejected(checked.reason)
        is SourceEvidenceCheck.Unavailable -> Verification.Indeterminate(checked.reason)
    }
}
