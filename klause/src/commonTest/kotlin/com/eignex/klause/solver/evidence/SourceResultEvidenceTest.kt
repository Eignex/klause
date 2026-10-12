package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import kotlin.test.Test
import kotlin.test.assertIs

class SourceResultEvidenceTest {
    private val scope = SourceScope(SourceRevision("original-source", "exact-affine-v1"))
    private val context = SourceProofContext(scope, emptySet(), mapOf(
        "conflict" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE)),
    ))

    @Test
    fun `an engine attestation cannot supply independent proof coverage`() {
        val result = SourceResultEvidence(scope, emptyList(), listOf(SourceEngineAttestation("complete-engine", SourceClaim.Refutation)))

        assertIs<SourceEvidenceCheck.Unavailable>(result.verify(SourceClaim.Refutation, context))
    }

    @Test
    fun `evidence for another result claim cannot establish a refutation`() {
        val consequence = SourceClaim.Consequence(SourceInequality(emptyMap(), BigFraction.ONE))
        val result = SourceResultEvidence(scope, listOf(SourceEvidence(scope, consequence, SourceDerivation.Premise("conflict"))))

        assertIs<SourceEvidenceCheck.Unavailable>(result.verify(SourceClaim.Refutation, context))
    }

    @Test
    fun `mismatched captured invocation scopes reject the result package`() {
        val other = scope.copy(condition = SourceCondition(bools = mapOf(0 to true)))
        val result = SourceResultEvidence(scope, listOf(SourceEvidence(other, SourceClaim.Refutation, SourceDerivation.Premise("conflict"))))

        assertIs<SourceEvidenceCheck.Rejected>(result.verify(SourceClaim.Refutation, context))
    }

    @Test
    fun `publication captures a producer owned derivation list`() {
        val derivations = mutableListOf(SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Premise("conflict")))
        val result = SourceResultEvidence(scope, derivations)
        derivations.clear()

        assertIs<SourceEvidenceCheck.Checked>(result.verify(SourceClaim.Refutation, context))
    }
}
