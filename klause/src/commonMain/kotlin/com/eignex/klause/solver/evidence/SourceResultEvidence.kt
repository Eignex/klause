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
class SourceResultEvidence private constructor(
    /** Original revision, assumptions, declared semantics and objective scope. */
    val scope: SourceScope,
    derivations: List<SourceEvidence>,
    attestations: List<SourceEngineAttestation>,
    /** Invocation admitting terminal engine completion; null withholds mapped terminal authority. */
    val engineScope: SourceScope?,
    mappings: List<SourceMappingEvidence>,
) {
    /** Capture original-scoped claims and separate engine completion labels. */
    constructor(
        scope: SourceScope,
        derivations: List<SourceEvidence>,
        attestations: List<SourceEngineAttestation> = emptyList(),
    ) : this(scope, derivations, attestations, scope, emptyList())

    private val capturedMappings = mappings.toList()
    /** Retained reduced-source evidence and optional transformation justifications. */
    val mappings: List<SourceMappingEvidence> get() = capturedMappings.toList()

    internal fun withMappings(
        engineScope: SourceScope?, mappings: List<SourceMappingEvidence>,
    ): SourceResultEvidence = SourceResultEvidence(scope, capturedDerivations, capturedAttestations,
        engineScope, capturedMappings + mappings)

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
    ): SourceEvidenceCheck = verify(claim, context, cancellation, SourceProofAllowance(100_000, 128))

    internal fun verify(
        claim: SourceClaim, context: SourceProofContext, cancellation: Cancellation, allowance: SourceProofAllowance,
    ): SourceEvidenceCheck {
        if (scope != context.scope || capturedDerivations.any { it.scope != scope }) {
            return SourceEvidenceCheck.Rejected("completed result evidence has mismatched original-source scopes")
        }
        val limited = Cancellation { cancellation() || !allowance.consume(0) }
        val verifier = SourceEvidenceVerifier(context)
        var mismatch: SourceEvidenceCheck? = null
        var unavailable: SourceEvidenceCheck.Unavailable? = null
        for (evidence in capturedDerivations) {
            if (limited()) return SourceEvidenceCheck.Unavailable("completed evidence checking allowance exhausted")
            if (!sameSourceClaimKind(evidence.claim, claim)) continue
            when (val matching = matchSourceClaims(evidence.claim, claim, limited)) {
                SourceEvidenceCheck.Checked -> when (val checked = verifier.verify(evidence, limited, allowance)) {
                    SourceEvidenceCheck.Checked -> return checked
                    is SourceEvidenceCheck.Rejected -> return checked
                    is SourceEvidenceCheck.Unavailable -> unavailable = checked
                }
                is SourceEvidenceCheck.Unavailable -> unavailable = matching
                is SourceEvidenceCheck.Rejected -> mismatch = matching
            }
        }
        return unavailable ?: mismatch
            ?: SourceEvidenceCheck.Unavailable("completed result has no derivation for the requested claim")
    }
}
