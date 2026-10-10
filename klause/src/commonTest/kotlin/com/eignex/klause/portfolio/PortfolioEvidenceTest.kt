package com.eignex.klause.portfolio

import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.incumbent.CandidateVerifier
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
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
        portfolio.evidenceVerification = PortfolioEvidence(model) { Verification.Indeterminate("verification interrupted") }
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
}
