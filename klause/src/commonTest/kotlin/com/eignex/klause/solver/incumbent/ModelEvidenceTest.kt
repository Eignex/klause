package com.eignex.klause.solver.incumbent

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ModelEvidenceTest {
    @Test
    fun `evidence from a different model is rejected for every claim kind`() {
        val model = ModelIdentity.of(Any())
        val other = ModelIdentity.of(Any())
        val verifier = ModelEvidenceVerifier<String, Int>(model, CandidateVerifier.trusting())
        val claims: List<ModelEvidence<String, Int>> = listOf(
            ModelEvidence.Witness(other, Candidate("point", 7)),
            ModelEvidence.Bound(other, 7, null),
            ModelEvidence.Infeasible(other, null),
            ModelEvidence.Unbounded(
                other, Candidate("point", 7), null,
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
        val source = Problem(0, 1, arrayOf(IntDomain(7, 9)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val otherObjective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val other = ModelIdentity.of(source, otherObjective)
        val foreign = EngineInvocation(source.prepareExecutionPlan(otherObjective), other, otherObjective)
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(foreign, 7.0))
        val verifier = ModelEvidenceVerifier<Sample, Double>(model, CandidateVerifier.trusting())
        val claim = ModelEvidence.Bound(model, 7.0, certificate)

        assertIs<Verification.Rejected>(verifier.verify(claim))
    }

    @Test
    fun `a certificate cannot substitute another proof kind`() {
        val source = Problem(0, 1, arrayOf(IntDomain(7, 9)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val certificate = EvidenceCertificate.published(CompletedEnginePublication.ObjectiveBound(invocation, 7.0))
        val verifier = ModelEvidenceVerifier<Sample, Double>(model, CandidateVerifier.trusting())
        val claim = ModelEvidence.Infeasible(model, certificate)

        assertIs<Verification.Rejected>(verifier.verify(claim))
    }

    @Test
    fun `ray verification preserves an indeterminate witness check`() {
        val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
        val model = ModelIdentity.of(source, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), exactReals = listOf(BigFraction.ZERO))
        val result = MinimizeResult.Unbounded(point, 0.0, listOf(BigFraction.MINUS_ONE))
        val certificate = assertNotNull(EvidenceCertificate.completed(model, EvidenceKind.Unbounded,
            CompletedEngineResult.Minimize(invocation, result)))
        val verifier = ModelEvidenceVerifier<Sample, Double>(model) { Verification.Indeterminate("point is undecided") }
        val claim = ModelEvidence.Unbounded(
            model, Candidate(point, 0.0), certificate,
        )

        assertIs<Verification.Indeterminate>(verifier.verify(claim, invocation = invocation))
    }

    @Test
    fun `a matching completed package is accepted`() {
        val source = Problem(0, 1, arrayOf(IntDomain(7, 9)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val producer = ModelIdentity.of(source, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), producer, objective)
        val point = Sample(booleanArrayOf(), longArrayOf(7))
        val result = MinimizeResult.Optimal(point, 7.0)
        val certificate = assertNotNull(EvidenceCertificate.completed(producer, EvidenceKind.Bound,
            CompletedEngineResult.Minimize(invocation, result)))
        val verifier = ModelEvidenceVerifier<Sample, Double>(model, CandidateVerifier.trusting())
        val claim = ModelEvidence.Bound(producer, 7.0, certificate)

        assertIs<Verification.Accepted<*, *>>(verifier.verify(claim, invocation = invocation))
    }
    @Test
    fun `a conditional refutation cannot be imported into its containing source invocation`() {
        val model = ModelIdentity.of(Any())
        val original = SourceScope(SourceRevision("original", "exact-affine"))
        val conditional = original.copy(condition = SourceCondition(bools = mapOf(0 to true)))
        val certificate = assertNotNull(EvidenceCertificate.completed(model, EvidenceKind.Infeasible,
            CompletedEngineResult.Solve(conditional, SolveResult.Unsat())))
        val verifier = ModelEvidenceVerifier<String, Int>(model, CandidateVerifier.trusting())
        val claim = ModelEvidence.Infeasible(model, certificate)

        val checked = verifier.verify(claim, original)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `a consumer without a source invocation cannot import a scoped refutation`() {
        val model = ModelIdentity.of(Any())
        val scope = SourceScope(SourceRevision("original", "exact-affine"))
        val certificate = assertNotNull(EvidenceCertificate.completed(model, EvidenceKind.Infeasible,
            CompletedEngineResult.Solve(scope, SolveResult.Unsat())))
        val verifier = ModelEvidenceVerifier<String, Int>(model, CandidateVerifier.trusting())

        val checked = verifier.verify(ModelEvidence.Infeasible(model, certificate))

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `a completed runtime bound cannot authorize a different numeric publication`() {
        val source = Problem(0, 1, arrayOf(IntDomain(1, 3)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val binding = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val certificate = assertNotNull(EvidenceCertificate.completed(model, EvidenceKind.Bound,
            CompletedEngineResult.Minimize(binding, MinimizeResult.Optimal(
                Sample(booleanArrayOf(), longArrayOf(1)), 1.0))))
        val verifier = ModelEvidenceVerifier<Sample, Double>(model, CandidateVerifier.trusting())

        val checked = verifier.verify(ModelEvidence.Bound(model, 2.0, certificate), invocation = binding)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `a completed witness cannot authorize another carried point`() {
        val source = Problem(0, 1, arrayOf(IntDomain(1, 3)), emptyArray())
        val model = ModelIdentity.of(source)
        val binding = EngineInvocation(source.prepareExecutionPlan(), model)
        val point = Sample(booleanArrayOf(), longArrayOf(1))
        val certificate = assertNotNull(EvidenceCertificate.completed(model, EvidenceKind.Witness,
            CompletedEngineResult.Solve(binding, SolveResult.Sat(point))))
        val other = Sample(booleanArrayOf(), longArrayOf(2))
        val verifier = ModelEvidenceVerifier<Sample, Unit>(model, CandidateVerifier.trusting())

        val checked = verifier.verify(ModelEvidence.Witness(model, Candidate(other, Unit), certificate))

        assertIs<Verification.Rejected>(checked)
    }

}
