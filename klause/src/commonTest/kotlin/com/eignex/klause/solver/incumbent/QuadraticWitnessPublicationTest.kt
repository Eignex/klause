package com.eignex.klause.solver.incumbent

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.QuadraticCoefficient
import com.eignex.klause.ir.QuadraticObjectiveSpec
import com.eignex.klause.ir.QuadraticObjectiveTerm
import com.eignex.klause.ir.QuadraticVariable
import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.QuadraticEngineInvocation
import com.eignex.klause.solver.evidence.QuadraticEngineSource
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.result.QuadraticIncumbent
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.objective.toQuadraticObjectiveSpec
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class QuadraticWitnessPublicationTest {
    private val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
    private val variable = QuadraticVariable.Int(SourceIntId(0))
    private val objective = QuadraticObjectiveSpec(listOf(
        QuadraticObjectiveTerm(QuadraticCoefficient.ONE, variable, variable),
    ))
    private val invocation = QuadraticEngineInvocation(QuadraticEngineSource.ExactIr(source), objective,
        ObjectiveSense.MINIMIZE, SourceCondition())
    private val arithmetic = AlgebraicArithmetic()
    private val point = QuadraticIncumbent(Sample(booleanArrayOf(), longArrayOf(2)),
        arithmetic.rational(BigFraction.ofLong(4)))
    private val publication = QuadraticWitnessPublication(invocation, point)
    private val scope = SourceScope(SourceRevision("original", "klause-ir-exact-v1"))
    private val context = assertNotNull(source.sourceProofContext(scope))
    private val sourceChecker: (Sample) -> SourceEvidenceCheck = { sample ->
        SourceEvidenceVerifier(context).verify(SourceEvidence(scope, SourceClaim.Witness(sample),
            SourceDerivation.SourceWitness))
    }

    @Test
    fun `a normalized quadratic witness preserves its actual affine objective binding`() {
        val affine = LinearObjective(intCoefficients = longArrayOf(1))
        val normalized = affine.toQuadraticObjectiveSpec()
        val native = QuadraticEngineInvocation(invocation.source, normalized, invocation.sense,
            invocation.condition, affine)
        val candidate = point.copy(value = arithmetic.rational(BigFraction.ofLong(2)))

        val checked = QuadraticWitnessPublication(native, candidate).verify(native, candidate,
            arithmetic, sourceChecker)

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

    @Test
    fun `a quadratic witness cannot substitute an equal shaped original affine objective`() {
        val affine = LinearObjective(intCoefficients = longArrayOf(1))
        val normalized = affine.toQuadraticObjectiveSpec()
        val native = QuadraticEngineInvocation(invocation.source, normalized, invocation.sense,
            invocation.condition, affine)
        val candidate = point.copy(value = arithmetic.rational(BigFraction.ofLong(2)))
        val foreign = QuadraticEngineInvocation(invocation.source, normalized, invocation.sense,
            invocation.condition, LinearObjective(intCoefficients = longArrayOf(1)))

        val checked = QuadraticWitnessPublication(native, candidate).verify(foreign, candidate,
            arithmetic, sourceChecker)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `a quadratic publication checks its actual source point and exact value`() {
        val checked = publication.verify(invocation, point, arithmetic, sourceChecker)

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

    @Test
    fun `a quadratic publication rejects substituted points and values`() {
        val candidates = listOf(
            point.copy(sample = Sample(booleanArrayOf(), longArrayOf(1))),
            point.copy(value = arithmetic.rational(BigFraction.ONE)),
        )

        for (candidate in candidates) {
            val checked = publication.verify(invocation, candidate, arithmetic, sourceChecker)

            assertIs<SourceEvidenceCheck.Rejected>(checked)
        }
    }

    @Test
    fun `a quadratic publication rejects foreign sources objectives and directions`() {
        val invocations = listOf(
            QuadraticEngineInvocation(QuadraticEngineSource.ExactIr(
                Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())), objective,
                invocation.sense, invocation.condition),
            QuadraticEngineInvocation(invocation.source, QuadraticObjectiveSpec(objective.terms),
                invocation.sense, invocation.condition),
            QuadraticEngineInvocation(invocation.source, objective, ObjectiveSense.MAXIMIZE, invocation.condition),
        )

        for (expected in invocations) {
            val checked = publication.verify(expected, point, arithmetic, sourceChecker)

            assertIs<SourceEvidenceCheck.Rejected>(checked)
        }
    }

    @Test
    fun `a restricted quadratic point can satisfy its containing query`() {
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val condition = SourceCondition(lower = mapOf(coordinate to BigFraction.ofLong(2)),
            upper = mapOf(coordinate to BigFraction.ofLong(2)))
        val restricted = QuadraticEngineInvocation(invocation.source, objective, invocation.sense, condition)

        val checked = QuadraticWitnessPublication(restricted, point).verify(invocation, point,
            arithmetic, sourceChecker)

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

    @Test
    fun `a quadratic publication enforces both producer and consumer conditions`() {
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val condition = SourceCondition(upper = mapOf(coordinate to BigFraction.ONE))
        val restricted = QuadraticEngineInvocation(invocation.source, objective, invocation.sense, condition)
        val cases = listOf(QuadraticWitnessPublication(restricted, point) to invocation, publication to restricted)

        for ((produced, expected) in cases) {
            val checked = produced.verify(expected, point, arithmetic, sourceChecker)

            assertIs<SourceEvidenceCheck.Rejected>(checked)
        }
    }

    @Test
    fun `a quadratic publication cannot bypass original source validation`() {
        val invalid = point.copy(sample = Sample(booleanArrayOf(), longArrayOf(4)),
            value = arithmetic.rational(BigFraction.ofLong(16)))

        val checked = QuadraticWitnessPublication(invocation, invalid).verify(invocation, invalid,
            arithmetic, sourceChecker)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `a quadratic publication checks its value against the original objective`() {
        val invalid = point.copy(value = arithmetic.rational(BigFraction.ONE))

        val checked = QuadraticWitnessPublication(invocation, invalid).verify(invocation, invalid,
            arithmetic, sourceChecker)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `unavailable source checking withholds quadratic witness admission`() {
        val checked = publication.verify(invocation, point, arithmetic) {
            SourceEvidenceCheck.Unavailable("unsupported source semantics")
        }

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `cancelled checking withholds quadratic witness admission`() {
        val cancelled = AlgebraicArithmetic(PolynomialArithmetic(Cancellation { true }))

        val checked = publication.verify(invocation, point, cancelled, sourceChecker)

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `quadratic publication issuance rejects another model objective`() {
        val foreign = ModelIdentity.of(source, QuadraticObjectiveSpec(objective.terms))

        assertFailsWith<IllegalArgumentException> { EvidenceCertificate.published(foreign, publication) }
    }

    @Test
    fun `a native quadratic witness without capture has unavailable independent derivation`() {
        val model = ModelIdentity.of(source, objective)
        val certificate = EvidenceCertificate.published(model, publication)

        val checked = certificate.verifyDerivation(SourceClaim.Witness(point.sample), context)

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `generic model admission cannot bypass quadratic source checking`() {
        val model = ModelIdentity.of(source, objective)
        val certificate = EvidenceCertificate.published(model, publication)
        val verifier = ModelEvidenceVerifier<Sample, Double>(model, CandidateVerifier.trusting())

        val checked = verifier.verify(ModelEvidence.Witness(model, Candidate(point.sample, 4.0), certificate))

        assertIs<Verification.Indeterminate>(checked)
    }
}
