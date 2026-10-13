package com.eignex.klause.solver.incumbent

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class CompletedEnginePublicationTest {
    private val source = Problem(0, 2, Array(2) { IntDomain(0, 5) }, emptyArray())
    private val objective = LinearObjective(intCoefficients = longArrayOf(1, 0))
    private val plan = source.prepareExecutionPlan(objective)
    private val model = ModelIdentity.of(source, objective)
    private val invocation = EngineInvocation(plan, model, objective)
    private val verifier = ModelEvidenceVerifier<Sample, Double>(model, CandidateVerifier.trusting())

    @Test
    fun `native objective publication retains its actual value`() {
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(invocation, 2.0))

        val checked = verifier.verify(ModelEvidence.Bound(model, 2.0, certificate), invocation = invocation)

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `native objective publication cannot authorize another value`() {
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(invocation, 2.0))

        for (value in listOf(1.0, 3.0)) {
            val checked = verifier.verify(ModelEvidence.Bound(model, value, certificate), invocation = invocation)

            assertIs<Verification.Rejected>(checked)
        }
    }

    @Test
    fun `native publication requires the consumer runtime`() {
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(invocation, 2.0))

        val checked = verifier.verify(ModelEvidence.Bound(model, 2.0, certificate))

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `native publication without derivation capture remains independently unavailable`() {
        val scope = SourceScope(SourceRevision("original", "klause-ir-exact-v1"),
            objective = SourceObjective("objective", invocation.sense))
        val captured = EngineInvocation(plan, model, objective, scope = scope)
        val context = assertNotNull(source.sourceProofContext(scope, objective))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(captured, 2.0))

        val checked = certificate.verifyDerivation(SourceClaim.Bound(BigFraction.ofLong(2)), context)

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `native publication cannot cross execution assumptions`() {
        val restricted = EngineInvocation(plan, model, objective,
            executionAssumptions = Assumptions(ints = mapOf(0 to 2L)))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(restricted, 2.0))

        val checked = verifier.verify(ModelEvidence.Bound(model, 2.0, certificate), invocation = invocation)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `native integer publication retains the coordinate and both endpoints`() {
        val identity = model.forObjective(null)
        val windows = ModelEvidenceVerifier<Sample, Double>(identity, CandidateVerifier.trusting())
        val certificate = EvidenceCertificate.published(
            CompletedEnginePublication.IntegerWindow(invocation, 0, 1, 4))

        val checked = windows.verify(ModelEvidence.IntegerWindow(identity, 0, 1, 4, certificate),
            invocation = invocation)

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `native integer publication cannot authorize another coordinate or endpoint`() {
        val identity = model.forObjective(null)
        val windows = ModelEvidenceVerifier<Sample, Double>(identity, CandidateVerifier.trusting())
        val certificate = EvidenceCertificate.published(
            CompletedEnginePublication.IntegerWindow(invocation, 0, 1, 4))

        for ((id, lower, upper) in listOf(Triple(1, 1L, 4L), Triple(0, 2L, 4L), Triple(0, 1L, 3L))) {
            val checked = windows.verify(ModelEvidence.IntegerWindow(identity, id, lower, upper, certificate),
                invocation = invocation)

            assertIs<Verification.Rejected>(checked)
        }
    }

    @Test
    fun `native empty integer window retains the reported contradiction`() {
        val identity = model.forObjective(null)
        val windows = ModelEvidenceVerifier<Sample, Double>(identity, CandidateVerifier.trusting())
        val certificate = EvidenceCertificate.published(
            CompletedEnginePublication.IntegerWindow(invocation, 0, 3, 2))

        val checked = windows.verify(ModelEvidence.IntegerWindow(identity, 0, 3, 2, certificate),
            invocation = invocation)

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `native integer publication cannot substitute an objective bound`() {
        val identity = model.forObjective(null)
        val windows = ModelEvidenceVerifier<Sample, Double>(identity, CandidateVerifier.trusting())
        val certificate = EvidenceCertificate.published(
            CompletedEnginePublication.IntegerWindow(invocation, 0, 1, 4))

        val checked = windows.verify(ModelEvidence.Bound(identity, 4.0, certificate), invocation = invocation)

        assertIs<Verification.Rejected>(checked)
    }
}
