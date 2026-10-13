@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.incumbent.Candidate
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class OpenLinearPortfolioResultsTest {
    @Test
    fun `parallel strict limits retain their typed original direction and an interior receipt`() {
        val model = Problem(0, 0, emptyArray(), arrayOf(Linear(intArrayOf(), doubleArrayOf(),
            intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.0, strict = true),
            Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0,
                strict = true)), numRealVars = 1, realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))

        for (maximize in listOf(false, true)) for (cores in listOf(1, 2)) {
            val request = OpenTheoryRequest(model, objective, maximize)
            val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request,
                TheoryParams(openWorkLimit = 1000, maxFlips = 0), FiniteEngine.MIXED, cores, null))

            val bounded = assertIs<OpenTheoryOptimum.Bounded>(result.result)
            val authority = assertNotNull(bounded.linearResult)
            val limit = assertIs<SourceClaim.Limit>(assertNotNull(authority.terminal).claim)
            assertEquals(SourceValue.Rational(if (maximize) BigFraction.ONE else BigFraction.ZERO), limit.value)
            val receipt = assertNotNull(result.acceptedIncumbent)
            val point = assertIs<SourceValue.Rational>(receipt.value).value
            assertTrue(point > BigFraction.ZERO && point < BigFraction.ONE)
            assertSame(receipt.sample, authority.incumbent)
            assertEquals(point, authority.value)
            assertEquals(if (maximize) point.negated() else point, bounded.value)
        }
    }

    @Test
    fun `cancellation after installation retains the checked root bound without completing a proof`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val request = OpenTheoryRequest(model, LinearObjective(realCoefficients = doubleArrayOf(1.0)))
        var cancelled = false
        var observed: SourceIncumbentPublication? = null

        val result = assertIs<OpenTheoryExecution.Optimize>(OpenTheoryPipeline.execute(request,
            TheoryParams(openWorkLimit = 1000, maxFlips = 0, cancellation = Cancellation { cancelled }),
            FiniteEngine.MIXED, 1) { observed = it; cancelled = true })

        val bounded = assertIs<OpenTheoryOptimum.Bounded>(result.result)
        val authority = assertNotNull(bounded.linearResult)
        assertNull(authority.terminal)
        assertEquals(SourceValue.Rational(BigFraction.ZERO),
            assertIs<SourceClaim.Bound>(assertNotNull(authority.bound).claim).value)
        assertSame(observed, result.acceptedIncumbent)
        assertEquals(BigFraction.ZERO, authority.value)
        assertEquals(TerminationReason.Cancelled, bounded.reason)
    }

    @Test
    fun `a ray base stays paired with its value while a better shared incumbent survives`() {
        val model = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(Double.POSITIVE_INFINITY))
        val objective = LinearObjective(realCoefficients = doubleArrayOf(-1.0))
        val request = OpenTheoryRequest(model, objective)
        val context = assertNotNull(request.linearAuthorityContext(Cancellation.Never))
        val identity = ModelIdentity.of(model, objective)
        val binding = EngineInvocation(request.preparedPlan, identity, objective, scope = context.scope)
        val values = OpenSourceObjective(objective, context = context)
        val incumbents = values.incumbents()
        val standing = Sample(BooleanArray(0), LongArray(0), doubleArrayOf(2.0), listOf(BigFraction.ofLong(2)))
        assertIs<Verification.Accepted<*, *>>(context.linearWitnessVerifier(Cancellation.Never)
            .verify(Candidate(standing, null)))
        incumbents.exchange.offer(standing, assertNotNull(values.value(standing)))
        val search = LinearPortfolioSearch(RationalTheoryDescent(model, objective, TheoryParams(), context) {
            incumbents.exchange.current()?.let { it.assignment to it.objective.execution }
        }, binding)
        val worker = PortfolioWorker.ofMinimize("linear", 0) { _, _, _, _ -> error("retained only") }
            .bindEvidence(identity).bindInvocation(binding)
        val results = OpenLinearPortfolioResults(binding, context, values, incumbents, Cancellation.Never)
        results.retain(worker, AtomicReference<LinearPortfolioSearch?>(search))

        val scalar = search.use { assertNotNull(it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> }) }
        val publication = assertNotNull(results.check(worker, search))
        assertIs<Verification.Accepted<*, *>>(publication.verification)
        publication.accepted()
        val folded = assertIs<OpenTheoryOptimum.Unbounded>(results.fold(scalar))

        val ray = assertIs<SourceClaim.Ray>(assertNotNull(folded.linearResult?.terminal).claim)
        assertTrue(ray.sample.exactReals?.single() != standing.exactReals?.single())
        assertEquals(context.linearValue(ray.sample, Cancellation.Never), folded.value)
        assertEquals(ray.sample.exactReals?.single().toString(), folded.witness.realValue(0))
        assertSame(standing, folded.linearResult?.incumbent)
        assertEquals(BigFraction.ofLong(-2), folded.linearResult?.value)
    }
}
