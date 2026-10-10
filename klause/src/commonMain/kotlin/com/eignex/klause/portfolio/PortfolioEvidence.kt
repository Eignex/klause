package com.eignex.klause.portfolio

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelEvidence
import com.eignex.klause.solver.incumbent.ModelEvidenceVerifier
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification

internal class PortfolioEvidence(
    private val model: ModelIdentity,
    private val witnessVerifier: CandidateVerifier<Sample, Double?>,
) {
    private val verifier = ModelEvidenceVerifier(model, witnessVerifier)

    fun witness(worker: PortfolioWorker, sample: Sample, value: Double?): Verification<Sample, Double?> {
        val identity = worker.evidenceModel ?: return Verification.Indeterminate("worker has no model identity")
        val candidate = Candidate(sample, value)
        return when (val verdict = verifier.verify(ModelEvidence.Witness(identity, candidate))) {
            is Verification.Accepted -> Verification.Accepted(candidate)
            is Verification.Rejected -> verdict
            is Verification.Indeterminate -> verdict
        }
    }

    fun proof(
        worker: PortfolioWorker,
        kind: EvidenceKind,
        sample: Sample? = null,
        value: Double? = null,
    ): Verification<Unit, Unit> {
        val identity = worker.evidenceModel ?: return Verification.Indeterminate("worker has no model identity")
        val certificate = worker.certificateFor(kind)
        val evidence: ModelEvidence<Sample, Double?> = when (kind) {
            EvidenceKind.Bound -> ModelEvidence.Bound(identity, value, certificate)
            EvidenceKind.Infeasible -> ModelEvidence.Infeasible(identity, certificate)
            EvidenceKind.Unbounded -> ModelEvidence.Unbounded(
                identity, Candidate(checkNotNull(sample), value), certificate,
            )
            EvidenceKind.Witness -> error("a witness is checked separately")
        }
        return when (val verdict = verifier.verify(evidence)) {
            is Verification.Accepted -> Verification.Accepted(Candidate(Unit, Unit))
            is Verification.Rejected -> verdict
            is Verification.Indeterminate -> verdict
        }
    }
}
