package com.eignex.klause.solver.result

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.QuadraticEngineInvocation
import com.eignex.klause.solver.evidence.QuadraticEngineSource
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    fun `a native quadratic certificate cannot bind a different engine source`() {
        val model = Problem(0, 0, emptyArray(), emptyArray())
        val invocation = QuadraticEngineInvocation(QuadraticEngineSource.ExactIr(model),
            QuadraticObjectiveSpec(emptyList()), ObjectiveSense.MINIMIZE, SourceCondition())

        assertFailsWith<IllegalArgumentException> {
            QuadraticCertificate.engine(identity, invocation, QuadraticClaim.Infeasible,
                SourceDerivation.Unavailable("source traversal capture unavailable"))
        }
    }

    @Test
    fun `a native quadratic certificate cannot bind another model objective`() {
        val model = Problem(0, 0, emptyArray(), emptyArray())
        val objective = QuadraticObjectiveSpec(emptyList())
        val invocation = QuadraticEngineInvocation(QuadraticEngineSource.ExactIr(model), objective,
            ObjectiveSense.MINIMIZE, SourceCondition())
        val foreign = ModelIdentity.of(model, QuadraticObjectiveSpec(emptyList()))

        assertFailsWith<IllegalArgumentException> {
            QuadraticCertificate.engine(foreign, invocation, QuadraticClaim.Infeasible,
                SourceDerivation.Unavailable("source traversal capture unavailable"))
        }
    }

    @Test
    fun `a native quadratic certificate matches another binding of the same invocation`() {
        val model = Problem(0, 0, emptyArray(), emptyArray())
        val objective = QuadraticObjectiveSpec(emptyList())
        val invocation = QuadraticEngineInvocation(QuadraticEngineSource.ExactIr(model), objective,
            ObjectiveSense.MINIMIZE, SourceCondition())
        val certificate = QuadraticCertificate.engine(ModelIdentity.of(model, objective), invocation,
            QuadraticClaim.Infeasible, SourceDerivation.Unavailable("source traversal capture unavailable"))
        val other = QuadraticEngineInvocation(QuadraticEngineSource.ExactIr(model), objective,
            ObjectiveSense.MINIMIZE, SourceCondition())

        assertTrue(certificate.matchesInvocation(other))
    }

    @Test
    fun `a native quadratic certificate refuses changed invocation fields`() {
        val model = Problem(1, 0, emptyArray(), emptyArray())
        val objective = QuadraticObjectiveSpec(emptyList())
        val source = QuadraticEngineSource.ExactIr(model)
        val invocation = QuadraticEngineInvocation(source, objective, ObjectiveSense.MINIMIZE, SourceCondition())
        val certificate = QuadraticCertificate.engine(ModelIdentity.of(model, objective), invocation,
            QuadraticClaim.Infeasible, SourceDerivation.Unavailable("source traversal capture unavailable"))
        val otherSource = QuadraticEngineSource.ExactIr(Problem(1, 0, emptyArray(), emptyArray()))
        val others = listOf(
            QuadraticEngineInvocation(otherSource, objective, invocation.sense, invocation.condition),
            QuadraticEngineInvocation(source, QuadraticObjectiveSpec(emptyList()), invocation.sense,
                invocation.condition),
            QuadraticEngineInvocation(source, objective, ObjectiveSense.MAXIMIZE, invocation.condition),
            QuadraticEngineInvocation(source, objective, invocation.sense, SourceCondition(bools = mapOf(0 to true))),
        )

        for (other in others) assertFalse(certificate.matchesInvocation(other))
    }
}
