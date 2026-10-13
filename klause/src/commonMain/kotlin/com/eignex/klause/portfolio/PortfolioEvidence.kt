package com.eignex.klause.portfolio

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.checkWitness
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.CompletedEngineResult
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelEvidence
import com.eignex.klause.solver.incumbent.ModelEvidenceVerifier
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.util.Cancellation

internal class PortfolioEvidence(
    private val model: ModelIdentity,
    private val witnessVerifier: CandidateVerifier<Sample, Double?>,
) {
    private var sourceScope: SourceScope? = null
    private var scopeCancellation: Cancellation = Cancellation.Never

    fun bindSourceScope(
        scope: SourceScope, cancellation: Cancellation = Cancellation.Never,
    ): PortfolioEvidence = also {
        require(sourceScope == null || sourceScope == scope) { "portfolio cannot change its source invocation" }
        sourceScope = scope
        scopeCancellation = scopeCancellation or cancellation
    }

    private val verifier = ModelEvidenceVerifier(model, witnessVerifier)

    fun witness(worker: PortfolioWorker, sample: Sample, value: Double?): Verification<Sample, Double?> {
        val identity = worker.evidenceModel ?: return Verification.Indeterminate("worker has no model identity")
        val candidate = Candidate(sample, value)
        when (val condition = sourceScope?.condition?.checkWitness(sample, scopeCancellation)) {
            is SourceEvidenceCheck.Rejected -> return Verification.Rejected(condition.reason)
            is SourceEvidenceCheck.Unavailable -> return Verification.Indeterminate(condition.reason)
            SourceEvidenceCheck.Checked, null -> Unit
        }
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
        return proof(identity, worker.certificateFor(kind), kind, sample, value)
    }

    fun proof(
        worker: PortfolioWorker,
        kind: EvidenceKind,
        completion: CompletedEngineResult,
    ): Verification<Unit, Unit> {
        val expected = sourceScope ?: return Verification.Indeterminate("portfolio has no original-source scope")
        if (completion.scope != expected || worker.evidenceScope != expected ||
            completion.evidence?.scope?.let { it != expected } == true
        ) return Verification.Rejected("completion belongs to a different original-source invocation")
        val identity = worker.evidenceModel ?: return Verification.Indeterminate("worker has no model identity")
        val sample = when (completion) {
            is CompletedEngineResult.Solve -> (completion.result as? SolveResult.Sat)?.assignment
            is CompletedEngineResult.Minimize -> completion.result.assignment
        }
        val value = (completion as? CompletedEngineResult.Minimize)?.result?.objectiveValue
        if (sample != null) {
            when (val checked = witness(worker, sample, value)) {
                is Verification.Rejected -> return checked
                is Verification.Indeterminate -> return checked
                is Verification.Accepted -> Unit
            }
        }
        return proof(identity, worker.certificateFor(kind, completion), kind, sample, value)
    }

    private fun proof(
        identity: ModelIdentity,
        certificate: EvidenceCertificate?,
        kind: EvidenceKind,
        sample: Sample?,
        value: Double?,
    ): Verification<Unit, Unit> {
        val evidence: ModelEvidence<Sample, Double?> = when (kind) {
            EvidenceKind.Bound -> ModelEvidence.Bound(identity, value, certificate)
            EvidenceKind.Infeasible -> ModelEvidence.Infeasible(identity, certificate)
            EvidenceKind.Unbounded -> ModelEvidence.Unbounded(
                identity, Candidate(checkNotNull(sample), value), certificate,
            )
            EvidenceKind.Witness -> error("a witness is checked separately")
        }
        return when (val verdict = verifier.verify(evidence, sourceScope)) {
            is Verification.Accepted -> Verification.Accepted(Candidate(Unit, Unit))
            is Verification.Rejected -> verdict
            is Verification.Indeterminate -> verdict
        }
    }
}
