package com.eignex.klause.solver.incumbent

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.SourceWorkerBinding
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceResultCapture
import com.eignex.klause.solver.evidence.SourceResultEvidence
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.evidence.sourceProofContext
import com.eignex.klause.solver.evidence.sourceResultCapture
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.TerminationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CompletedEngineResultTest {
    private val scope = SourceScope(SourceRevision("original", "exact-affine"))
    private val identity = ModelIdentity.of(Any())

    @Test
    fun `completed refutation should not issue a different claim kind`() {
        val completion = CompletedEngineResult.Solve(scope, SolveResult.Unsat())

        assertNull(EvidenceCertificate.completed(identity, EvidenceKind.Unbounded, completion))
    }

    @Test
    fun `worker should not certify a completion for a foreign scope`() {
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Unsat() }
            .bindSource(SourceWorkerBinding(identity, scope = scope))
        val foreign = scope.copy(revision = scope.revision.copy(identity = "foreign"))
        val completion = CompletedEngineResult.Solve(foreign, SolveResult.Unsat())

        assertNull(worker.certificateFor(EvidenceKind.Infeasible, completion))
    }

    @Test
    fun `completed certificate should not confer derivation coverage by itself`() {
        val completion = CompletedEngineResult.Solve(scope, SolveResult.Unsat())
        val certificate = assertNotNull(EvidenceCertificate.completed(identity, EvidenceKind.Infeasible, completion))
        val context = SourceProofContext(
            scope,
            emptySet(),
            mapOf(
                "contradiction" to SourcePremise(SourceInequality(emptyMap(), BigFraction.MINUS_ONE)),
            ),
        )

        assertIs<SourceEvidenceCheck.Unavailable>(certificate.verifyDerivation(SourceClaim.Refutation, context))
    }

    @Test
    fun `witness-only worker should not certify a refutation`() {
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Unsat() }
            .bindSource(SourceWorkerBinding(identity, scope = scope, provesResults = false)).bindEvidence(identity)
        val completion = CompletedEngineResult.Solve(scope, SolveResult.Unsat())

        assertNull(worker.certificateFor(EvidenceKind.Infeasible, completion))
    }

    @Test
    fun `search exhaustion without a cutoff should not issue a bound`() {
        val scoped = scope.copy(objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val result = MinimizeResult.Unknown(TerminationReason.SearchExhausted, SolveStats.EMPTY)

        val certificate = EvidenceCertificate.completed(
            identity,
            EvidenceKind.Bound,
            CompletedEngineResult.Minimize(scoped, result),
        )

        assertNull(certificate)
    }

    @Test
    fun `evidence for another witness should be rejected`() {
        val model = Problem(0, 1, arrayOf(IntDomain(1, 3)), emptyArray())
        val scoped = SourceScope(SourceRevision("original-ir", "klause-ir-exact-v1"))
        val context = assertNotNull(model.sourceProofContext(scoped))
        val actual = Sample(booleanArrayOf(), longArrayOf(1))
        val other = Sample(booleanArrayOf(), longArrayOf(2))
        val otherResult = SourceResultCapture(context).solve(SolveResult.Sat(other), "fixture")
        val result = SolveResult.Sat(actual, evidence = otherResult.evidence)
        val certificate = assertNotNull(
            EvidenceCertificate.completed(
                identity,
                EvidenceKind.Witness,
                CompletedEngineResult.Solve(scoped, result),
            ),
        )

        val checked = certificate.verifyDerivation(SourceClaim.Witness(other), context)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `exhausted completion should reject a different cutoff`() {
        val scoped = scope.copy(objective = SourceObjective("x", ObjectiveSense.MINIMIZE))
        val result = MinimizeResult.Unknown(TerminationReason.SearchExhausted)
        val cutoff = SourceClaim.Bound(BigFraction.ONE)
        val certificate = assertNotNull(
            EvidenceCertificate.completed(
                identity,
                EvidenceKind.Bound,
                CompletedEngineResult.Minimize(scoped, result, cutoff),
            ),
        )
        val context = SourceProofContext(scoped, emptySet(), emptyMap())

        val checked = certificate.verifyDerivation(SourceClaim.Bound(BigFraction.ofLong(2)), context)

        assertIs<SourceEvidenceCheck.Rejected>(checked)
    }

    @Test
    fun `derivations from another scope should not issue a certificate`() {
        val foreign = scope.copy(condition = SourceCondition(bools = mapOf(0 to true)))
        val evidence = SourceResultEvidence(
            scope,
            listOf(
                SourceEvidence(
                    foreign,
                    SourceClaim.Refutation,
                    SourceDerivation.Unavailable("conditional traversal"),
                ),
            ),
        )
        val result = SolveResult.Unsat(evidence = evidence)

        val certificate = EvidenceCertificate.completed(
            identity,
            EvidenceKind.Infeasible,
            CompletedEngineResult.Solve(scope, result),
        )

        assertNull(certificate)
    }

    @Test
    fun `unbounded completion should check its witness point`() {
        val model = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val scoped = SourceScope(
            SourceRevision("original-ir", "klause-ir-exact-v1"),
            objective = SourceObjective("negative-x", ObjectiveSense.MINIMIZE),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        val context = assertNotNull(model.sourceProofContext(scoped, objective))
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), exactReals = listOf(BigFraction.ZERO))
        val evidence = SourceResultEvidence(
            scoped,
            listOf(
                SourceEvidence(
                    scoped,
                    SourceClaim.Witness(point),
                    SourceDerivation.SourceWitness,
                ),
            ),
        )
        val result = MinimizeResult.Unbounded(point, 0.0, listOf(BigFraction.ONE), evidence = evidence)
        val completion = CompletedEngineResult.Minimize(scoped, result)
        val certificate = assertNotNull(EvidenceCertificate.completed(identity, EvidenceKind.Witness, completion))

        val checked = certificate.verifyDerivation(SourceClaim.Witness(point), context)

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

    @Test
    fun `uncaptured runtime completion should be unavailable for source checks`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        val model = ModelIdentity.of(source)
        val binding = EngineInvocation(plan, model)
        val certificate = assertNotNull(
            EvidenceCertificate.completed(
                model,
                EvidenceKind.Infeasible,
                CompletedEngineResult.Solve(binding, SolveResult.Unsat()),
            ),
        )
        val context = SourceProofContext(scope, emptySet(), emptyMap())

        val checked = certificate.verifyDerivation(SourceClaim.Refutation, context)

        assertIs<SourceEvidenceCheck.Unavailable>(checked)
    }

    @Test
    fun `exhausted runtime optimization should issue a bound only with its execution cutoff`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val plan = source.prepareExecutionPlan(objective)
        val model = ModelIdentity.of(source, objective)
        val binding = EngineInvocation(plan, model, objective)
        val result = MinimizeResult.Unknown(TerminationReason.SearchExhausted)

        val certificate = EvidenceCertificate.completed(
            model,
            EvidenceKind.Bound,
            CompletedEngineResult.Minimize(binding, result, executionCutoff = 2.0),
        )

        assertNotNull(certificate)
        assertNull(
            EvidenceCertificate.completed(
                model,
                EvidenceKind.Bound,
                CompletedEngineResult.Minimize(binding, result),
            ),
        )
    }

    @Test
    fun `captured ray should snapshot its direction`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        val scoped = SourceScope(
            SourceRevision("ray-source", "klause-ir-exact-v1"),
            objective = SourceObjective("negative-x", ObjectiveSense.MINIMIZE),
        )
        val context = assertNotNull(source.sourceProofContext(scoped, objective))
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0), listOf(BigFraction.ZERO))
        val direction = mutableListOf(BigFraction.ONE)
        val result = SourceResultCapture(context).minimize(
            MinimizeResult.Unbounded(point, 0.0, direction),
            objective,
            "fixture-engine",
        )
        val completion = CompletedEngineResult.Minimize(scoped, result)
        direction[0] = BigFraction.MINUS_ONE
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val certificate = assertNotNull(EvidenceCertificate.completed(identity, EvidenceKind.Unbounded, completion))

        val checked = certificate.verifyDerivation(
            SourceClaim.Ray(point, mapOf(coordinate to BigFraction.ONE)),
            context,
        )

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }

    @Test
    fun `explicit source capture should be checkable on a runtime completion`() {
        val source = Problem(0, 1, arrayOf(IntDomain(1, 3)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val plan = source.prepareExecutionPlan(objective)
        val capture = assertNotNull(
            plan.sourceResultCapture(
                SourceRevision("runtime-original", "klause-ir-exact-v1"),
                objectiveIdentity = "x",
            ),
        )
        val model = ModelIdentity.of(source, objective)
        val binding = EngineInvocation(plan, model, objective, scope = capture.context.scope)
        val point = Sample(booleanArrayOf(), longArrayOf(1))
        val proof = SourceDerivation.Optimum(SourceDerivation.SourceWitness, SourceDerivation.Premise("int/0/lower"))
        val result = capture.minimize(MinimizeResult.Optimal(point, 1.0), objective, "fixture-engine", proof)
        val certificate = assertNotNull(
            EvidenceCertificate.completed(
                model,
                EvidenceKind.Bound,
                CompletedEngineResult.Minimize(binding, result),
            ),
        )

        val checked = certificate.verifyDerivation(
            SourceClaim.Optimal(point, SourceValue.Rational(BigFraction.ONE)),
            capture.context,
        )

        assertEquals(SourceEvidenceCheck.Checked, checked)
    }
}
