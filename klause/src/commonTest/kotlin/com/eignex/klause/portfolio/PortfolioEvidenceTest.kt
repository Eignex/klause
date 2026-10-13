package com.eignex.klause.portfolio

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
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

}
