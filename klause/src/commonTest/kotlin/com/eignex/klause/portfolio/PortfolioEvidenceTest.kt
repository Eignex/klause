package com.eignex.klause.portfolio

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.CompletedEnginePublication
import com.eignex.klause.solver.incumbent.CompletedEngineResult
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.EvidenceCertificate
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.prepareExecutionPlan
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PortfolioEvidenceTest {

    @Test
    fun `unrestricted complete exhaustion should retain an optimum without a shared cutoff`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 0)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1), constant = 7)
        val identity = ModelIdentity.of(source, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), identity, objective)
        val point = Sample(booleanArrayOf(), longArrayOf(0))
        val worker = PortfolioWorker.ofMinimize("exhausted", 0) { _, _, _, _ ->
            sequenceOf(MinimizeResult.BestFound(point, 7.0, TerminationReason.SearchExhausted))
        }.bindSource(SourceWorkerBinding(invocation, sharingInvocation = invocation)).also {
            it.controlsObjectiveCutoff = true
        }

        val result = Portfolio.thompson(listOf(worker)).use { it.minimize() }

        val optimum = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(point, optimum.sample)
        assertEquals(7.0, optimum.objective)
    }

    @Test
    fun `completion observation should retain native exhaustion before portfolio normalization`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 0)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1), constant = 7)
        val identity = ModelIdentity.of(source, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), identity, objective)
        val point = Sample(booleanArrayOf(), longArrayOf(0))
        val worker = PortfolioWorker.ofMinimize("exhausted", 0) { _, _, _, _ ->
            sequenceOf(MinimizeResult.BestFound(point, 7.0, TerminationReason.SearchExhausted))
        }.bindSource(SourceWorkerBinding(invocation, sharingInvocation = invocation)).also {
            it.controlsObjectiveCutoff = true
        }
        var observation: OptimizationCompletionObservation? = null

        Portfolio.thompson(listOf(worker)).use { portfolio ->
            portfolio.completionObserver = { observation = it }
            portfolio.minimize()
        }

        val captured = checkNotNull(observation)
        assertEquals(TerminationReason.SearchExhausted,
            assertIs<MinimizeResult.BestFound>(captured.nativeResult).reason)
        val completion = checkNotNull(captured.completion)
        assertIs<MinimizeResult.Optimal>(completion.result)
        assertEquals(invocation, completion.invocation)
        assertNull(captured.cutoff)
        assertIs<Verification.Accepted<*, *>>(captured.verification)
    }

    @Test
    fun `unrestricted exhaustion should retain refutation only for a complete bound worker`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(0, 0)),
            arrayOf(
                Linear(coeffs = intArrayOf(1), vars = intArrayOf(0), op = LinearOp.EQ, bound = 1),
            ),
        )
        val objective = LinearObjective()
        val identity = ModelIdentity.of(source, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), identity, objective)
        for ((proofs, controlled) in listOf(false to true, true to false, true to true)) {
            val worker = PortfolioWorker.ofMinimize("exhausted", 0) { bound, _, _, _ ->
                assertEquals(Double.POSITIVE_INFINITY, bound())
                sequenceOf(MinimizeResult.Unknown(TerminationReason.SearchExhausted))
            }.bindSource(SourceWorkerBinding(invocation, sharingInvocation = invocation, provesResults = proofs)).also {
                it.controlsObjectiveCutoff = controlled
            }

            val result = Portfolio.thompson(listOf(worker)).use { it.minimize() }

            if (proofs && controlled) {
                assertIs<MinimizeResult.Infeasible>(result)
            } else {
                assertIs<MinimizeResult.Unknown>(result)
            }
        }
    }

    @Test
    fun `a native witness without a publication should still satisfy its producer restrictions`() {
        val source = Problem(1, 0, emptyArray(), emptyArray())
        val model = ModelIdentity.of(source)
        val plan = source.prepareExecutionPlan()
        val containing = EngineInvocation(plan, model)
        val producer = EngineInvocation(plan, model, executionAssumptions = Assumptions(bools = mapOf(0 to true)))
        val point = Sample(booleanArrayOf(false), longArrayOf())
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }
            .bindSource(SourceWorkerBinding(producer))
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindInvocation(containing)

        val checked = verifier.witness(worker, point, null)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `portfolio admission should consume a restricted native witness publication`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val plan = source.prepareExecutionPlan(objective)
        val model = ModelIdentity.of(source, objective)
        val containing = EngineInvocation(plan, model, objective)
        val producer = EngineInvocation(plan, model, objective, Assumptions(ints = mapOf(0 to 2L)))
        val point = Sample(booleanArrayOf(), longArrayOf(2))
        point.witnessCertificate = EvidenceCertificate.published(
            CompletedEnginePublication.Witness(producer, point, 2.0),
        )
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }
            .bindSource(SourceWorkerBinding(producer))
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindInvocation(containing)

        val checked = verifier.witness(worker, point, 2.0)

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `portfolio admission should reject a foreign native prepared plan`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val model = ModelIdentity.of(source)
        val containing = EngineInvocation(source.prepareExecutionPlan(), model)
        val producer = EngineInvocation(source.prepareExecutionPlan(), model)
        val point = Sample(booleanArrayOf(), longArrayOf(2))
        point.witnessCertificate = EvidenceCertificate.published(
            CompletedEnginePublication.Witness(producer, point, null),
        )
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }
            .bindSource(SourceWorkerBinding(producer))
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindInvocation(containing)

        val checked = verifier.witness(worker, point, null)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `portfolio admission should reject a substituted native publication value`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val binding = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val point = Sample(booleanArrayOf(), longArrayOf(2))
        point.witnessCertificate = EvidenceCertificate.published(
            CompletedEnginePublication.Witness(binding, point, 2.0),
        )
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }
            .bindSource(SourceWorkerBinding(binding))
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindInvocation(binding)

        val checked = verifier.witness(worker, point, 3.0)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `portfolio admission should reject a violated native producer condition`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val model = ModelIdentity.of(source)
        val plan = source.prepareExecutionPlan()
        val containing = EngineInvocation(plan, model)
        val producer = EngineInvocation(plan, model, sourceAssumptions = Assumptions(ints = mapOf(0 to 1L)))
        val point = Sample(booleanArrayOf(), longArrayOf(2))
        point.witnessCertificate = EvidenceCertificate.published(
            CompletedEnginePublication.Witness(producer, point, null),
        )
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }
            .bindSource(SourceWorkerBinding(producer))
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindInvocation(containing)

        val checked = verifier.witness(worker, point, null)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `a mismatched model witness should be quarantined without proving infeasibility`() {
        val model = ModelIdentity.of(Any())
        val worker = PortfolioWorker.ofSolve("foreign", 0) { _, _ ->
            SolveResult.Sat(Sample(booleanArrayOf(true), LongArray(0)))
        }.bindEvidence(ModelIdentity.of(Any()))
        val portfolio = Portfolio.thompson(listOf(worker))
        portfolio.evidenceVerification = PortfolioEvidence(model, CandidateVerifier.trusting())

        val result = portfolio.use { it.solve() }

        assertIs<SolveResult.Unknown>(result)
        assertEquals(1L, result.stats.portfolio.arms.single().faults)
    }

    @Test
    fun `indeterminate candidates should not become incumbents or justify exhaustion`() {
        val model = ModelIdentity.of(Any())
        val sample = Sample(booleanArrayOf(true), LongArray(0))
        val worker = PortfolioWorker.ofMinimize("undecided", 0) { _, _, _, _ ->
            sequenceOf(MinimizeResult.Optimal(sample, 1.0))
        }.bindEvidence(model)
        val portfolio = Portfolio.thompson(listOf(worker))
        portfolio.evidenceVerification = PortfolioEvidence(model) {
            Verification.Indeterminate("verification interrupted")
        }
        var publications = 0

        val result = portfolio.use { it.minimize(onImprovement = { publications++ }) }

        assertIs<MinimizeResult.Unknown>(result)
        assertEquals(0, publications)
        assertEquals(0L, result.stats.portfolio.arms.single().faults)
    }

    @Test
    fun `withheld terminal certificates should retain witnesses without publishing proofs`() {
        val model = ModelIdentity.of(Any())
        val sample = Sample(booleanArrayOf(true), LongArray(0))
        val claims = listOf(
            MinimizeResult.Optimal(sample, 1.0),
            MinimizeResult.Unbounded(sample, 1.0, listOf(BigFraction.MINUS_ONE)),
            MinimizeResult.BestFound(sample, 1.0, TerminationReason.SearchExhausted),
        )

        for (claim in claims) {
            val worker = PortfolioWorker.ofMinimize("withheld", 0) { _, _, _, _ -> sequenceOf(claim) }
                .bindEvidence(model, provesResults = false)
            val result = Portfolio.thompson(listOf(worker)).use { it.minimize() }

            assertEquals(1.0, assertIs<MinimizeResult.BestFound>(result).objective)
        }
    }

    @Test
    fun `withheld infeasibility certificates should leave the model unknown`() {
        val model = ModelIdentity.of(Any())
        val worker = PortfolioWorker.ofMinimize("withheld", 0) { _, _, _, _ -> sequenceOf(MinimizeResult.Infeasible()) }
            .bindEvidence(model, provesResults = false)

        val result = Portfolio.thompson(listOf(worker)).use { it.minimize() }

        assertIs<MinimizeResult.Unknown>(result)
        assertEquals(0L, result.stats.portfolio.arms.single().faults)
    }

    @Test
    fun `outer binding should not promote a witness-only worker to proof authority`() {
        val identity = ModelIdentity.of(Any())
        val sample = Sample(booleanArrayOf(), longArrayOf())
        val worker = PortfolioWorker.ofMinimize("witness-only", 0) { _, _, _, _ ->
            sequenceOf(MinimizeResult.Optimal(sample, 1.0))
        }.bindEvidence(identity, provesResults = false).bindEvidence(identity)

        val result = Portfolio.thompson(listOf(worker)).use { it.minimize() }

        assertIs<MinimizeResult.BestFound>(result)
    }

    @Test
    fun `model binding without a runtime invocation should not prove infeasibility`() {
        val source = Problem(0, 0, emptyArray(), emptyArray())
        val model = ModelIdentity.of(source)
        val worker = PortfolioWorker.ofSolve("unbound", 0) { _, _ -> SolveResult.Unsat() }.bindEvidence(model)

        val result = Portfolio.thompson(listOf(worker)).use { it.solve() }

        assertIs<SolveResult.Unknown>(result)
        assertEquals(0L, result.stats.portfolio.arms.single().faults)
    }

    @Test
    fun `model binding without a runtime invocation should retain only an optimization witness`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val point = Sample(booleanArrayOf(), longArrayOf(2))
        val worker = PortfolioWorker.ofMinimize("unbound", 0) { _, _, _, _ ->
            sequenceOf(MinimizeResult.Optimal(point, 2.0))
        }.bindEvidence(model)

        val result = Portfolio.thompson(listOf(worker)).use { it.minimize() }

        assertEquals(point, assertIs<MinimizeResult.BestFound>(result).sample)
        assertEquals(0L, result.stats.portfolio.arms.single().faults)
    }

    @Test
    fun `runtime bound source completion should retain its optimization terminal`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val model = ModelIdentity.of(source, objective)
        val invocation = EngineInvocation(source.prepareExecutionPlan(objective), model, objective)
        val point = Sample(booleanArrayOf(), longArrayOf(0))
        val worker = PortfolioWorker.ofMinimize("bound", 0) { _, _, _, _ ->
            sequenceOf(MinimizeResult.Optimal(point, 0.0))
        }.bindSource(SourceWorkerBinding(invocation))
        val portfolio = Portfolio.thompson(listOf(worker))
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindInvocation(invocation)
        portfolio.evidenceVerification = verifier

        val result = portfolio.use { it.minimize() }

        assertEquals(point, assertIs<MinimizeResult.Optimal>(result).sample)
    }

    @Test
    fun `exact objective ordering should preserve improvements hidden by floating projection`() {
        for (real in listOf(false, true)) {
            val objective = if (real) {
                LinearObjective(constant = 1L shl 62, realCoefficients = doubleArrayOf(-1.0))
            } else {
                LinearObjective(constant = 1L shl 62, intCoefficients = longArrayOf(-1))
            }
            val first = if (real) {
                Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), listOf(BigFraction.ONE))
            } else {
                Sample(booleanArrayOf(), longArrayOf(1))
            }
            val optimum = if (real) {
                Sample(
                    booleanArrayOf(),
                    longArrayOf(),
                    doubleArrayOf(2.0),
                    listOf(BigFraction.ofLong(2)),
                )
            } else {
                Sample(booleanArrayOf(), longArrayOf(2))
            }
            val source = if (real) {
                Problem(
                    0,
                    0,
                    emptyArray(),
                    emptyArray(),
                    numRealVars = 1,
                    realLower = doubleArrayOf(1.0),
                    realUpper = doubleArrayOf(2.0),
                )
            } else {
                Problem(0, 1, arrayOf(IntDomain(1, 2)), emptyArray())
            }
            val identity = ModelIdentity.of(source, objective)
            val binding = EngineInvocation(source.prepareExecutionPlan(objective), identity, objective)
            val complete = PortfolioWorker.ofMinimize("bt/exact", 0) { _, _, _, _ ->
                sequenceOf(
                    MinimizeResult.BestFound(
                        first,
                        objective.evaluateExact(first).toDouble(),
                        TerminationReason.BudgetExhausted,
                    ),
                    MinimizeResult.Optimal(optimum, objective.evaluateExact(optimum).toDouble()),
                )
            }.bindSource(SourceWorkerBinding(binding))
            val portfolio = Portfolio.thompson(listOf(complete))
            portfolio.exactLinearObjective = objective
            portfolio.evidenceVerification = PortfolioEvidence(identity, CandidateVerifier.trusting())
                .bindInvocation(binding)

            val result = portfolio.use { it.minimize() }

            assertEquals(
                objective.evaluateExact(optimum),
                objective.evaluateExact(assertIs<MinimizeResult.Optimal>(result).sample),
            )
        }
    }

    @Test
    fun `a foreign completion scope should be quarantined before engine admission`() {
        val model = ModelIdentity.of(Any())
        val scope = SourceScope(SourceRevision("original", "exact-affine"))
        val worker = PortfolioWorker.ofSolve("complete", 0) { _, _ -> SolveResult.Unsat() }
            .bindSource(SourceWorkerBinding(model, scope = scope))
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindSourceScope(scope)
        val foreign = scope.copy(revision = scope.revision.copy(identity = "foreign"))

        val checked = verifier.proof(
            worker,
            EvidenceKind.Infeasible,
            CompletedEngineResult.Solve(foreign, SolveResult.Unsat()),
        )

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `an invalid completed optimum witness should not gain admission from its label`() {
        val model = ModelIdentity.of(Any())
        val scope = SourceScope(SourceRevision("original", "exact-affine"))
        val worker = PortfolioWorker.ofSolve("complete", 0) { _, _ -> SolveResult.Unsat() }
            .bindSource(SourceWorkerBinding(model, scope = scope))
        val verifier = PortfolioEvidence(model) { Verification.Rejected("original source refutes this point") }
        verifier.bindSourceScope(scope)
        val result = MinimizeResult.Optimal(Sample(booleanArrayOf(), longArrayOf()), 0.0)

        val checked = verifier.proof(worker, EvidenceKind.Bound, CompletedEngineResult.Minimize(scope, result))

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `a base model witness should not enter an invocation whose conditions it violates`() {
        val model = ModelIdentity.of(Any())
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val absentInteger = SourceCoordinate(SourceCoordinate.Kind.Integer, 1)
        val absentReal = SourceCoordinate(SourceCoordinate.Kind.Real, 1)
        val conditions = listOf(
            SourceCondition(bools = mapOf(0 to true)),
            SourceCondition(lower = mapOf(integer to BigFraction.ONE)),
            SourceCondition(upper = mapOf(integer to BigFraction.MINUS_ONE)),
            SourceCondition(lower = mapOf(real to BigFraction.ONE)),
            SourceCondition(upper = mapOf(real to BigFraction.MINUS_ONE)),
            SourceCondition(bools = mapOf(1 to false)),
            SourceCondition(lower = mapOf(absentInteger to BigFraction.ZERO)),
            SourceCondition(lower = mapOf(absentReal to BigFraction.ZERO)),
        )
        val point = Sample(booleanArrayOf(false), longArrayOf(0), doubleArrayOf(0.0), listOf(BigFraction.ZERO))
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }.bindEvidence(model)

        for (condition in conditions) {
            val scope = SourceScope(SourceRevision("original", "exact-affine"), condition)
            val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindSourceScope(scope)

            assertIs<Verification.Rejected>(verifier.witness(worker, point, null))
        }
    }

    @Test
    fun `a scoped witness should satisfy Boolean integer and exact real conditions`() {
        val model = ModelIdentity.of(Any())
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val condition = SourceCondition(
            mapOf(0 to true),
            mapOf(integer to BigFraction.ONE, real to BigFraction.ONE),
            mapOf(integer to BigFraction.ONE, real to BigFraction.ONE),
        )
        val scope = SourceScope(SourceRevision("original", "exact-affine"), condition)
        val point = Sample(booleanArrayOf(true), longArrayOf(1), doubleArrayOf(1.0), listOf(BigFraction.ONE))
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }.bindEvidence(model)
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindSourceScope(scope)

        val checked = verifier.witness(worker, point, null)

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `an approximate real coordinate should not establish an invocation window`() {
        val model = ModelIdentity.of(Any())
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(
            SourceRevision("original", "exact-affine"),
            SourceCondition(lower = mapOf(real to BigFraction.ZERO)),
        )
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0))
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }.bindEvidence(model)
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindSourceScope(scope)

        val checked = verifier.witness(worker, point, null)

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `cancelling the containing invocation should leave its witness check indeterminate`() {
        val model = ModelIdentity.of(Any())
        val scope = SourceScope(SourceRevision("original", "exact-affine"), SourceCondition(bools = mapOf(0 to true)))
        val point = Sample(booleanArrayOf(false), longArrayOf())
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }.bindEvidence(model)
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting())
            .bindSourceScope(scope, Cancellation { true })

        val checked = verifier.witness(worker, point, null)

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `a completed optimum outside its source condition should not establish a bound`() {
        val model = ModelIdentity.of(Any())
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val scope = SourceScope(
            SourceRevision("original", "exact-affine"),
            SourceCondition(lower = mapOf(integer to BigFraction.ONE)),
            SourceObjective("x", ObjectiveSense.MINIMIZE),
        )
        val point = Sample(booleanArrayOf(), longArrayOf(0))
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }
            .bindSource(SourceWorkerBinding(model, scope = scope))
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindSourceScope(scope)
        val completion = CompletedEngineResult.Minimize(scope, MinimizeResult.Optimal(point, 0.0))

        val checked = verifier.proof(worker, EvidenceKind.Bound, completion)

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `a completed runtime optimum should need no invented source revision`() {
        val source = Problem(0, 1, arrayOf(IntDomain(1, 3)), emptyArray())
        val objective = LinearObjective(intCoefficients = longArrayOf(1))
        val plan = source.prepareExecutionPlan(objective)
        val model = ModelIdentity.of(source, objective)
        val binding = EngineInvocation(plan, model, objective)
        val point = Sample(booleanArrayOf(), longArrayOf(1))
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }
            .bindSource(SourceWorkerBinding(binding))
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindInvocation(binding)

        val checked = verifier.proof(
            worker,
            EvidenceKind.Bound,
            CompletedEngineResult.Minimize(binding, MinimizeResult.Optimal(point, 1.0)),
        )

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `a runtime completion should not cross an execution assumption boundary`() {
        val source = Problem(1, 0, emptyArray(), emptyArray())
        val plan = source.prepareExecutionPlan()
        val model = ModelIdentity.of(source)
        val binding = EngineInvocation(plan, model)
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Unsat() }
            .bindSource(SourceWorkerBinding(binding))
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindInvocation(binding)
        val conditional = EngineInvocation(
            plan,
            model,
            executionAssumptions = Assumptions(bools = mapOf(0 to true)),
        )

        val checked = verifier.proof(
            worker,
            EvidenceKind.Infeasible,
            CompletedEngineResult.Solve(conditional, SolveResult.Unsat()),
        )

        assertIs<Verification.Rejected>(checked)
    }
}
