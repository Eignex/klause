package com.eignex.klause.solver.incumbent

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation
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
    fun `conditioned invocation should not authorize a root objective bound`() {
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val condition = SourceCondition(lower = mapOf(coordinate to BigFraction.ONE))
        val producer = EngineInvocation(plan, model, objective, condition = condition)
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(producer, 2.0))

        val checked = verifier.verify(ModelEvidence.Bound(model, 2.0, certificate), invocation = invocation)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `conditioned witness should satisfy its root query`() {
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val condition = SourceCondition(lower = mapOf(coordinate to BigFraction.ONE))
        val producer = EngineInvocation(plan, model, objective, condition = condition)
        val point = Sample(booleanArrayOf(), longArrayOf(2, 1))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.Witness(producer, point, 2.0))

        val checked = verifier.verify(
            ModelEvidence.Witness(model, Candidate(point, 2.0), certificate),
            invocation = invocation,
        )

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `witness publication should accept its own point and value`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(2, 1))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.Witness(invocation, sample, 2.0))

        val checked = verifier.verify(
            ModelEvidence.Witness(model, Candidate(sample, 2.0), certificate),
            invocation = invocation,
        )

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `witness publication should reject another point or value`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(2, 1))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.Witness(invocation, sample, 2.0))

        for ((point, value) in listOf(sample.copy(ints = longArrayOf(3, 1)) to 2.0, sample to 3.0)) {
            val checked = verifier.verify(
                ModelEvidence.Witness(model, Candidate(point, value), certificate),
                invocation = invocation,
            )

            assertIs<Verification.Rejected>(checked)
        }
    }

    @Test
    fun `witness publication should be indeterminate without a consumer invocation`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(2, 1))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.Witness(invocation, sample, 2.0))

        val checked = verifier.verify(ModelEvidence.Witness(model, Candidate(sample, 2.0), certificate))

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `witness publication should enforce its source assumptions`() {
        val restricted = EngineInvocation(
            plan,
            model,
            objective,
            sourceAssumptions = Assumptions(ints = mapOf(0 to 3L)),
        )
        val sample = Sample(booleanArrayOf(), longArrayOf(2, 1))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.Witness(restricted, sample, 2.0))

        val checked = verifier.verify(
            ModelEvidence.Witness(model, Candidate(sample, 2.0), certificate),
            invocation = restricted,
        )

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `restricted witness should be accepted under its containing query`() {
        val restricted = EngineInvocation(
            plan,
            model,
            objective,
            sourceAssumptions = Assumptions(ints = mapOf(0 to 2L)),
        )
        val sample = Sample(booleanArrayOf(), longArrayOf(2, 1))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.Witness(restricted, sample, 2.0))

        val checked = verifier.verify(
            ModelEvidence.Witness(model, Candidate(sample, 2.0), certificate),
            invocation = invocation,
        )

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `witness publication should stay indeterminate when source validation is unavailable`() {
        val sample = Sample(booleanArrayOf(), longArrayOf(2, 1))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.Witness(invocation, sample, 2.0))
        val incomplete = ModelEvidenceVerifier<Sample, Double>(model) {
            Verification.Indeterminate("source unavailable")
        }

        val checked = incomplete.verify(
            ModelEvidence.Witness(model, Candidate(sample, 2.0), certificate),
            invocation = invocation,
        )

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `cancelled restriction check should withhold witness admission`() {
        val restricted = EngineInvocation(
            plan,
            model,
            objective,
            sourceAssumptions = Assumptions(ints = mapOf(0 to 2L)),
        )
        val sample = Sample(booleanArrayOf(), longArrayOf(2, 1))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.Witness(restricted, sample, 2.0))

        val checked = verifier.verify(
            ModelEvidence.Witness(model, Candidate(sample, 2.0), certificate),
            invocation = restricted,
            cancellation = Cancellation { true },
        )

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `objective bound publication should accept its own value`() {
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(invocation, 2.0))

        val checked = verifier.verify(ModelEvidence.Bound(model, 2.0, certificate), invocation = invocation)

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `objective bound publication should reject another value`() {
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(invocation, 2.0))

        for (value in listOf(1.0, 3.0)) {
            val checked = verifier.verify(ModelEvidence.Bound(model, value, certificate), invocation = invocation)

            assertIs<Verification.Rejected>(checked)
        }
    }

    @Test
    fun `objective bound publication should be indeterminate without a consumer invocation`() {
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(invocation, 2.0))

        val checked = verifier.verify(ModelEvidence.Bound(model, 2.0, certificate))

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `publication without derivation capture should be unavailable for source checks`() {
        val scope = SourceScope(
            SourceRevision("original", "klause-ir-exact-v1"),
            objective = SourceObjective("objective", invocation.sense),
        )
        val captured = EngineInvocation(plan, model, objective, scope = scope)
        val context = assertNotNull(source.sourceProofContext(scope, objective))
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(captured, 2.0))

        val checked = certificate.verifyDerivation(SourceClaim.Bound(BigFraction.ofLong(2)), context)

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `publication should not cross execution assumptions`() {
        val restricted = EngineInvocation(
            plan,
            model,
            objective,
            executionAssumptions = Assumptions(ints = mapOf(0 to 2L)),
        )
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(restricted, 2.0))

        val checked = verifier.verify(ModelEvidence.Bound(model, 2.0, certificate), invocation = invocation)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `integer window publication should accept its own coordinate and endpoints`() {
        val identity = model.forObjective(null)
        val windows = ModelEvidenceVerifier<Sample, Double>(identity, CandidateVerifier.trusting())
        val certificate = EvidenceCertificate.published(
            CompletedEnginePublication.IntegerWindow(invocation, 0, 1, 4),
        )

        val checked = windows.verify(
            ModelEvidence.IntegerWindow(identity, 0, 1, 4, certificate),
            invocation = invocation,
        )

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `integer window publication should reject another coordinate or endpoint`() {
        val identity = model.forObjective(null)
        val windows = ModelEvidenceVerifier<Sample, Double>(identity, CandidateVerifier.trusting())
        val certificate = EvidenceCertificate.published(
            CompletedEnginePublication.IntegerWindow(invocation, 0, 1, 4),
        )

        for ((id, lower, upper) in listOf(Triple(1, 1L, 4L), Triple(0, 2L, 4L), Triple(0, 1L, 3L))) {
            val checked = windows.verify(
                ModelEvidence.IntegerWindow(identity, id, lower, upper, certificate),
                invocation = invocation,
            )

            assertIs<Verification.Rejected>(checked)
        }
    }

    @Test
    fun `empty integer window publication should be accepted as reported`() {
        val identity = model.forObjective(null)
        val windows = ModelEvidenceVerifier<Sample, Double>(identity, CandidateVerifier.trusting())
        val certificate = EvidenceCertificate.published(
            CompletedEnginePublication.IntegerWindow(invocation, 0, 3, 2),
        )

        val checked = windows.verify(
            ModelEvidence.IntegerWindow(identity, 0, 3, 2, certificate),
            invocation = invocation,
        )

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `integer window publication should not substitute an objective bound`() {
        val identity = model.forObjective(null)
        val windows = ModelEvidenceVerifier<Sample, Double>(identity, CandidateVerifier.trusting())
        val certificate = EvidenceCertificate.published(
            CompletedEnginePublication.IntegerWindow(invocation, 0, 1, 4),
        )

        val checked = windows.verify(ModelEvidence.Bound(identity, 4.0, certificate), invocation = invocation)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `completed optimum should not authorize integer windows`() {
        val identity = model.forObjective(null)
        val windows = ModelEvidenceVerifier<Sample, Double>(identity, CandidateVerifier.trusting())
        val scope = SourceScope(
            SourceRevision("original", "klause-ir-exact-v1"),
            objective = SourceObjective("objective", invocation.sense),
        )
        val certificate = assertNotNull(
            EvidenceCertificate.completed(
                identity,
                EvidenceKind.Bound,
                CompletedEngineResult.Minimize(
                    scope,
                    MinimizeResult.Optimal(Sample(booleanArrayOf(), longArrayOf(0, 0)), 0.0),
                ),
            ),
        )

        val checked = windows.verify(ModelEvidence.IntegerWindow(identity, 1, 0, 0, certificate), scope)

        assertIs<Verification.Rejected>(checked)
    }
}
