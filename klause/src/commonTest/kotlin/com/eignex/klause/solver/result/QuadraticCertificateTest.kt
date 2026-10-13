package com.eignex.klause.solver.result

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceResultEvidence
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.incumbent.ModelIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class QuadraticCertificateTest {
    private val scope = SourceScope(SourceRevision("original", "exact-affine"))
    private val context = SourceProofContext(scope, emptySet(), mapOf(
        "contradiction" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE)),
    ))
    private val evidence = SourceResultEvidence(scope, listOf(SourceEvidence(scope, SourceClaim.Refutation,
        SourceDerivation.Premise("contradiction"))))
    private val identity = ModelIdentity.of(Any())

    @Test
    fun `a quadratic certificate cannot verify a different completion kind from its package`() {
        for (claim in QuadraticClaim.entries.filter { it != QuadraticClaim.Infeasible }) {
            val original = QuadraticCertificate.attested(identity, scope, claim)
            val certificate = QuadraticCertificate.captured(original, claim, evidence)

            val checked = certificate.verify(SourceClaim.Refutation, context)

            assertIs<SourceEvidenceCheck.Rejected>(checked, claim.toString())
        }
    }

    @Test
    fun `a quadratic refutation certificate verifies its retained original contradiction`() {
        val original = QuadraticCertificate.attested(identity, scope, QuadraticClaim.Infeasible)
        val certificate = QuadraticCertificate.captured(original, QuadraticClaim.Infeasible, evidence)

        val checked = certificate.verify(SourceClaim.Refutation, context)

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }
}
