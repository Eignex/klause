package com.eignex.klause.solver.evidence

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertIs

class SourceResultEvidenceTest {
    private val scope = SourceScope(SourceRevision("original-source", "exact-affine-v1"))
    private val context = SourceProofContext(scope, emptySet(), mapOf(
        "conflict" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE)),
    ))

    @Test
    fun `an engine attestation cannot supply independent proof coverage`() {
        val result = SourceResultEvidence(scope, emptyList(),
            listOf(SourceEngineAttestation("complete-engine", SourceClaim.Refutation)))

        assertIs<SourceEvidenceCheck.Unavailable>(result.verify(SourceClaim.Refutation, context))
    }

    @Test
    fun `evidence for another result claim cannot establish a refutation`() {
        val consequence = SourceClaim.Consequence(SourceInequality(emptyMap(), BigFraction.ONE))
        val result = SourceResultEvidence(scope, listOf(SourceEvidence(scope, consequence,
            SourceDerivation.Premise("conflict"))))

        assertIs<SourceEvidenceCheck.Unavailable>(result.verify(SourceClaim.Refutation, context))
    }

    @Test
    fun `mismatched captured invocation scopes reject the result package`() {
        val other = scope.copy(condition = SourceCondition(bools = mapOf(0 to true)))
        val result = SourceResultEvidence(scope, listOf(SourceEvidence(other, SourceClaim.Refutation,
            SourceDerivation.Premise("conflict"))))

        assertIs<SourceEvidenceCheck.Rejected>(result.verify(SourceClaim.Refutation, context))
    }

    @Test
    fun `publication captures a producer owned derivation list`() {
        val derivations = mutableListOf(SourceEvidence(scope, SourceClaim.Refutation,
            SourceDerivation.Premise("conflict")))
        val result = SourceResultEvidence(scope, derivations)
        derivations.clear()

        assertIs<SourceEvidenceCheck.Checked>(result.verify(SourceClaim.Refutation, context))
    }

    @Test
    fun `unavailable local capture cannot mask a supported worker import`() {
        val missing = SourceEvidence(scope, SourceClaim.Refutation,
            SourceDerivation.Unavailable("local traversal not captured"))
        val imported = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Import(
            SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Premise("conflict"))))

        for (derivations in listOf(listOf(missing, imported), listOf(imported, missing))) {
            val result = SourceResultEvidence(scope, derivations)

            assertIs<SourceEvidenceCheck.Checked>(result.verify(SourceClaim.Refutation, context))
        }
    }

    @Test
    fun `invalid proof proposals cannot fall back to a supported alternative`() {
        val invalid = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Combination(listOf(
            SourceDerivation.Combination.Term(BigFraction.MINUS_ONE, SourceDerivation.Premise("conflict")),
        )))
        val supported = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Premise("conflict"))
        val result = SourceResultEvidence(scope, listOf(invalid, supported))

        assertIs<SourceEvidenceCheck.Rejected>(result.verify(SourceClaim.Refutation, context))
    }

    @Test
    fun `unavailable capture cannot authorize an import with stronger conditions`() {
        val condition = scope.copy(condition = SourceCondition(bools = mapOf(0 to true)))
        val imported = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Import(
            SourceEvidence(condition, SourceClaim.Refutation, SourceDerivation.Premise("conflict"))))
        val missing = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Unavailable("missing traversal"))
        val result = SourceResultEvidence(scope, listOf(missing, imported))

        assertIs<SourceEvidenceCheck.Rejected>(result.verify(SourceClaim.Refutation, context))
    }

    @Test
    fun `alternative captures cannot renew the containing verification allowance`() {
        val missing = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Unavailable("missing traversal"))
        val supported = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Premise("conflict"))
        val result = SourceResultEvidence(scope, List(20) { missing } + supported)

        val checked = result.verify(SourceClaim.Refutation, context, Cancellation.Never, SourceProofAllowance(8, 128))

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `cancelled alternative capture checking remains unavailable`() {
        val supported = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Premise("conflict"))
        val result = SourceResultEvidence(scope, listOf(supported))

        val checked = result.verify(SourceClaim.Refutation, context, Cancellation { true })

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `an observed cancellation cannot be cleared by a later capture`() {
        val supported = SourceEvidence(scope, SourceClaim.Refutation, SourceDerivation.Premise("conflict"))
        val result = SourceResultEvidence(scope, listOf(supported, supported))
        var polls = 0

        val checked = result.verify(SourceClaim.Refutation, context, Cancellation { ++polls == 2 })

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }
}
