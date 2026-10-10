package com.eignex.klause.solver.incumbent

import kotlin.test.Test
import kotlin.test.assertIs

class ModelEvidenceTest {
    @Test
    fun `evidence from a different model is rejected for every claim kind`() {
        val model = ModelIdentity.of(Any())
        val other = ModelIdentity.of(Any())
        val verifier = ModelEvidenceVerifier<String, Int>(model, CandidateVerifier.trusting())
        val claims: List<ModelEvidence<String, Int>> = listOf(
            ModelEvidence.Witness(other, Candidate("point", 7)),
            ModelEvidence.Bound(other, 7, EvidenceCertificate.verified(other, EvidenceKind.Bound)),
            ModelEvidence.Infeasible(other, EvidenceCertificate.verified(other, EvidenceKind.Infeasible)),
            ModelEvidence.Unbounded(
                other, Candidate("point", 7), EvidenceCertificate.verified(other, EvidenceKind.Unbounded),
            ),
        )

        for (claim in claims) assertIs<Verification.Rejected>(verifier.verify(claim), claim.kind.name)
    }

    @Test
    fun `withheld proof packages leave claims indeterminate`() {
        val model = ModelIdentity.of(Any())
        val verifier = ModelEvidenceVerifier<String, Int>(model, CandidateVerifier.trusting())
        val claims: List<ModelEvidence<String, Int>> = listOf(
            ModelEvidence.Bound(model, 7, null),
            ModelEvidence.Infeasible(model, null),
            ModelEvidence.Unbounded(model, Candidate("point", 7), null),
        )

        for (claim in claims) assertIs<Verification.Indeterminate>(verifier.verify(claim), claim.kind.name)
    }

    @Test
    fun `a certificate for another objective cannot certify a bound`() {
        val source = Any()
        val model = ModelIdentity.of(source, Any())
        val other = ModelIdentity.of(source, Any())
        val verifier = ModelEvidenceVerifier<String, Int>(model, CandidateVerifier.trusting())
        val claim = ModelEvidence.Bound(model, 7, EvidenceCertificate.verified(other, EvidenceKind.Bound))

        assertIs<Verification.Rejected>(verifier.verify(claim))
    }

    @Test
    fun `a certificate cannot substitute another proof kind`() {
        val model = ModelIdentity.of(Any())
        val verifier = ModelEvidenceVerifier<String, Int>(model, CandidateVerifier.trusting())
        val claim = ModelEvidence.Infeasible(model, EvidenceCertificate.verified(model, EvidenceKind.Bound))

        assertIs<Verification.Rejected>(verifier.verify(claim))
    }

    @Test
    fun `ray verification preserves an indeterminate witness check`() {
        val model = ModelIdentity.of(Any())
        val verifier = ModelEvidenceVerifier<String, Int>(model) { Verification.Indeterminate("point is undecided") }
        val claim = ModelEvidence.Unbounded(
            model, Candidate("point", 7), EvidenceCertificate.verified(model, EvidenceKind.Unbounded),
        )

        assertIs<Verification.Indeterminate>(verifier.verify(claim))
    }

    @Test
    fun `a matching completed package is accepted`() {
        val source = Any()
        val objective = Any()
        val model = ModelIdentity.of(source, objective)
        val producer = ModelIdentity.of(source, objective)
        val verifier = ModelEvidenceVerifier<String, Int>(model, CandidateVerifier.trusting())
        val claim = ModelEvidence.Bound(producer, 7, EvidenceCertificate.verified(producer, EvidenceKind.Bound))

        assertIs<Verification.Accepted<*, *>>(verifier.verify(claim))
    }
}
