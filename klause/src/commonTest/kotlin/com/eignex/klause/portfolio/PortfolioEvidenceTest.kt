package com.eignex.klause.portfolio

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.CompletedEngineResult
import com.eignex.klause.solver.incumbent.EvidenceKind
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PortfolioEvidenceTest {
    @Test
    fun `a mismatched model witness is quarantined without proving infeasibility`() {
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
    fun `indeterminate candidates cannot become incumbents or justify exhaustion`() {
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
    fun `withheld terminal certificates retain witnesses without publishing proofs`() {
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
    fun `withheld infeasibility certificates leave the model unknown`() {
        val model = ModelIdentity.of(Any())
        val worker = PortfolioWorker.ofMinimize("withheld", 0) { _, _, _, _ -> sequenceOf(MinimizeResult.Infeasible()) }
            .bindEvidence(model, provesResults = false)

        val result = Portfolio.thompson(listOf(worker)).use { it.minimize() }

        assertIs<MinimizeResult.Unknown>(result)
        assertEquals(0L, result.stats.portfolio.arms.single().faults)
    }
    @Test
    fun `outer binding cannot promote a witness-only worker to proof authority`() {
        val identity = ModelIdentity.of(Any())
        val sample = Sample(booleanArrayOf(), longArrayOf())
        val worker = PortfolioWorker.ofMinimize("witness-only", 0) { _, _, _, _ ->
            sequenceOf(MinimizeResult.Optimal(sample, 1.0))
        }.bindEvidence(identity, provesResults = false).bindEvidence(identity)

        val result = Portfolio.thompson(listOf(worker)).use { it.minimize() }

        assertIs<MinimizeResult.BestFound>(result)
    }

    @Test
    fun `exact objective ordering preserves improvements hidden by floating projection`() {
        for (real in listOf(false, true)) {
            val objective = if (real) LinearObjective(constant = 1L shl 62, realCoefficients = doubleArrayOf(-1.0))
                else LinearObjective(constant = 1L shl 62, intCoefficients = longArrayOf(-1))
            val first = if (real) Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(1.0), listOf(BigFraction.ONE))
                else Sample(booleanArrayOf(), longArrayOf(1))
            val optimum = if (real) Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(2.0),
                listOf(BigFraction.ofLong(2))) else Sample(booleanArrayOf(), longArrayOf(2))
            val identity = ModelIdentity.of(Any(), objective)
            val complete = PortfolioWorker.ofMinimize("bt/exact", 0) { _, _, _, _ ->
                sequenceOf(MinimizeResult.BestFound(first, objective.evaluateExact(first).toDouble(),
                    TerminationReason.BudgetExhausted),
                    MinimizeResult.Optimal(optimum, objective.evaluateExact(optimum).toDouble()))
            }.bindEvidence(identity)
            val portfolio = Portfolio.thompson(listOf(complete))
            portfolio.exactLinearObjective = objective
            portfolio.evidenceVerification = PortfolioEvidence(identity, CandidateVerifier.trusting())

            val result = portfolio.use { it.minimize() }

            assertEquals(objective.evaluateExact(optimum),
                objective.evaluateExact(assertIs<MinimizeResult.Optimal>(result).sample))
        }
    }


    @Test
    fun `a foreign completion scope is quarantined before engine admission`() {
        val model = ModelIdentity.of(Any())
        val scope = SourceScope(SourceRevision("original", "exact-affine"))
        val worker = PortfolioWorker.ofSolve("complete", 0) { _, _ -> SolveResult.Unsat() }
            .bindEvidence(model).bindSourceScope(scope)
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindSourceScope(scope)
        val foreign = scope.copy(revision = scope.revision.copy(identity = "foreign"))

        val checked = verifier.proof(worker, EvidenceKind.Infeasible,
            CompletedEngineResult.Solve(foreign, SolveResult.Unsat()))

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `an invalid completed optimum witness cannot gain admission from its label`() {
        val model = ModelIdentity.of(Any())
        val scope = SourceScope(SourceRevision("original", "exact-affine"))
        val worker = PortfolioWorker.ofSolve("complete", 0) { _, _ -> SolveResult.Unsat() }
            .bindEvidence(model).bindSourceScope(scope)
        val verifier = PortfolioEvidence(model) { Verification.Rejected("original source refutes this point") }
        verifier.bindSourceScope(scope)
        val result = MinimizeResult.Optimal(Sample(booleanArrayOf(), longArrayOf()), 0.0)

        val checked = verifier.proof(worker, EvidenceKind.Bound, CompletedEngineResult.Minimize(scope, result))

        assertIs<Verification.Rejected>(checked)
    }

    @Test
    fun `a base model witness cannot enter an invocation whose conditions it violates`() {
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
    fun `a scoped witness satisfies Boolean integer and exact real conditions`() {
        val model = ModelIdentity.of(Any())
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val condition = SourceCondition(mapOf(0 to true), mapOf(integer to BigFraction.ONE, real to BigFraction.ONE),
            mapOf(integer to BigFraction.ONE, real to BigFraction.ONE))
        val scope = SourceScope(SourceRevision("original", "exact-affine"), condition)
        val point = Sample(booleanArrayOf(true), longArrayOf(1), doubleArrayOf(1.0), listOf(BigFraction.ONE))
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }.bindEvidence(model)
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindSourceScope(scope)

        val checked = verifier.witness(worker, point, null)

        assertIs<Verification.Accepted<*, *>>(checked)
    }

    @Test
    fun `an approximate real coordinate cannot establish an invocation window`() {
        val model = ModelIdentity.of(Any())
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val scope = SourceScope(SourceRevision("original", "exact-affine"),
            SourceCondition(lower = mapOf(real to BigFraction.ZERO)))
        val point = Sample(booleanArrayOf(), longArrayOf(), doubleArrayOf(0.0))
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }.bindEvidence(model)
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindSourceScope(scope)

        val checked = verifier.witness(worker, point, null)

        assertIs<Verification.Indeterminate>(checked)
    }

    @Test
    fun `cancelling the containing invocation leaves its witness check indeterminate`() {
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
    fun `a completed optimum outside its source condition cannot establish a bound`() {
        val model = ModelIdentity.of(Any())
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val scope = SourceScope(SourceRevision("original", "exact-affine"),
            SourceCondition(lower = mapOf(integer to BigFraction.ONE)),
            SourceObjective("x", ObjectiveSense.MINIMIZE))
        val point = Sample(booleanArrayOf(), longArrayOf(0))
        val worker = PortfolioWorker.ofSolve("fixture", 0) { _, _ -> SolveResult.Sat(point) }
            .bindEvidence(model).bindSourceScope(scope)
        val verifier = PortfolioEvidence(model, CandidateVerifier.trusting()).bindSourceScope(scope)
        val completion = CompletedEngineResult.Minimize(scope, MinimizeResult.Optimal(point, 0.0))

        val checked = verifier.proof(worker, EvidenceKind.Bound, completion)

        assertIs<Verification.Rejected>(checked)
    }

}
