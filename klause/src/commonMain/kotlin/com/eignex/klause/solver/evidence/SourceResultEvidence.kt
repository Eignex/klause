package com.eignex.klause.solver.evidence

import com.eignex.klause.util.Cancellation

/** Engine-reported completion, separate from independently checked original-source derivations. */
data class SourceEngineAttestation(
    /** Producing engine or source adapter. */
    val producer: String,
    /** Mathematical claim reported by that engine. */
    val claim: SourceClaim,
) {
    init { require(producer.isNotBlank()) }
}

/** Optional immutable evidence retained by a completed solver result. */
class SourceResultEvidence(
    /** Original revision, assumptions, declared semantics and objective scope. */
    val scope: SourceScope,
    derivations: List<SourceEvidence>,
    attestations: List<SourceEngineAttestation> = emptyList(),
) {
    private val capturedDerivations = derivations.toList()
    private val capturedAttestations = attestations.toList()
    /** Captured checkable claims; unavailable capture is represented explicitly in its derivation. */
    val derivations: List<SourceEvidence> get() = capturedDerivations.toList()
    /** Reported engine completions, which confer no independent derivation coverage. */
    val attestations: List<SourceEngineAttestation> get() = capturedAttestations.toList()

    /** Check the actual requested result claim rather than trusting a producer's completion label. */
    fun verify(
        claim: SourceClaim,
        context: SourceProofContext,
        cancellation: Cancellation = Cancellation.Never,
    ): SourceEvidenceCheck {
        if (scope != context.scope || capturedDerivations.any { it.scope != scope }) {
            return SourceEvidenceCheck.Rejected("completed result evidence has mismatched original-source scopes")
        }
        val evidence = capturedDerivations.firstOrNull { it.claim == claim }
            ?: return SourceEvidenceCheck.Unavailable("completed result has no derivation for the requested claim")
        return SourceEvidenceVerifier(context).verify(evidence, cancellation)
    }
}
