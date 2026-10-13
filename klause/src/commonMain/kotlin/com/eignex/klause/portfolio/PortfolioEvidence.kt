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
import com.eignex.klause.solver.incumbent.EngineInvocation
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
    private var invocation: EngineInvocation? = null

    fun bindInvocation(
        binding: EngineInvocation, cancellation: Cancellation = Cancellation.Never,
    ): PortfolioEvidence = also {
        require(model.matches(binding.model)) { "runtime invocation differs from the portfolio model" }
        require(invocation?.matches(binding) != false) { "portfolio cannot change its runtime invocation" }
        require(sourceScope == null || sourceScope == binding.scope) { "portfolio has another capture scope" }
        invocation = binding
        scopeCancellation = scopeCancellation or cancellation
        binding.scope?.let { bindSourceScope(it, cancellation) }
    }

    fun bindSourceScope(
        scope: SourceScope, cancellation: Cancellation = Cancellation.Never,
    ): PortfolioEvidence = also {
        require(sourceScope == null || sourceScope == scope) { "portfolio cannot change its source invocation" }
        require(invocation == null || invocation?.scope == scope) { "portfolio has another runtime capture scope" }
        sourceScope = scope
        scopeCancellation = scopeCancellation or cancellation
    }

    private val verifier = ModelEvidenceVerifier(model, witnessVerifier)

    fun witness(worker: PortfolioWorker, sample: Sample, value: Double?): Verification<Sample, Double?> {
        val identity = worker.evidenceModel ?: return Verification.Indeterminate("worker has no model identity")
        val candidate = Candidate(sample, value)
        val conditions = invocation?.checkWitness(sample, scopeCancellation)
            ?: sourceScope?.condition?.checkWitness(sample, scopeCancellation)
        when (val checked = conditions) {
            is SourceEvidenceCheck.Rejected -> return Verification.Rejected(checked.reason)
            is SourceEvidenceCheck.Unavailable -> return Verification.Indeterminate(checked.reason)
            SourceEvidenceCheck.Checked, null -> Unit
        }
        val certificate = sample.witnessCertificate?.takeIf { it.publication != null }
        val evidence = ModelEvidence.Witness(identity, candidate, certificate)
        return when (val verdict = verifier.verify(evidence, sourceScope, invocation, scopeCancellation)) {
            is Verification.Accepted -> Verification.Accepted(candidate)
            is Verification.Rejected -> verdict
            is Verification.Indeterminate -> verdict
        }
    }

    fun proof(
        worker: PortfolioWorker,
        kind: EvidenceKind,
        completion: CompletedEngineResult,
    ): Verification<Unit, Unit> {
        val runtime = invocation
        if (runtime != null) {
            val produced = completion.invocation
                ?: return Verification.Indeterminate("completion has no runtime invocation")
            if (!runtime.matches(produced) || worker.evidenceInvocation?.matches(runtime) != true) {
                return Verification.Rejected("completion belongs to another runtime invocation")
            }
        } else if (sourceScope == null) {
            return Verification.Indeterminate("portfolio has no source or runtime invocation")
        }
        val expected = sourceScope
        if (completion.scope != expected || worker.evidenceScope != expected ||
            completion.evidence?.scope?.let { it != expected } == true
        ) return Verification.Rejected("completion belongs to a different original-source invocation")
        val identity = worker.evidenceModel ?: return Verification.Indeterminate("worker has no model identity")
        val sample = when (completion) {
            is CompletedEngineResult.Solve -> (completion.result as? SolveResult.Sat)?.assignment
            is CompletedEngineResult.Minimize -> completion.result.assignment
        }
        val minimized = completion as? CompletedEngineResult.Minimize
        val pointValue = minimized?.result?.objectiveValue
        val value = if (kind == EvidenceKind.Bound) minimized?.executionBound else pointValue
        if (sample != null) {
            when (val checked = witness(worker, sample, pointValue)) {
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
        return when (val verdict = verifier.verify(evidence, sourceScope, invocation)) {
            is Verification.Accepted -> Verification.Accepted(Candidate(Unit, Unit))
            is Verification.Rejected -> verdict
            is Verification.Indeterminate -> verdict
        }
    }
}
