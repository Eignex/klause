package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.ArmFamily
import com.eignex.klause.portfolio.PortfolioEvidence
import com.eignex.klause.portfolio.PortfolioIncumbents
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.portfolio.SourceWorkerBinding
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCondition
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceObjective
import com.eignex.klause.solver.evidence.SourcePremise
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.SourceRevision
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.solver.evidence.SourceValue
import com.eignex.klause.solver.incumbent.EngineInvocation
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.LinearOptimizationResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinearPortfolioTerminalTest {
    private val model = Problem(
        0,
        0,
        emptyArray(),
        arrayOf(
            Linear(
                intArrayOf(),
                doubleArrayOf(),
                intArrayOf(0),
                doubleArrayOf(1.0),
                LinearOp.GE,
                0.0,
                strict = true,
            ),
        ),
        numRealVars = 1,
        realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
        realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
    )
    private val objective = LinearObjective(realCoefficients = doubleArrayOf(1.0))
    private val request = OpenTheoryRequest(model, objective)
    private val source = assertNotNull(request.linearAuthorityContext(Cancellation.Never))
    private val identity = ModelIdentity.of(model, objective)
    private val binding = EngineInvocation(request.preparedPlan, identity, scope = source.scope)

    @Test
    fun `a rejected runtime binding should close the unbound retained handle`() {
        val other = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(1.0),
        )
        val foreign = EngineInvocation(request.preparedPlan, ModelIdentity.of(other, objective), scope = source.scope)
        val descent = RationalTheoryDescent(model, objective, TheoryParams(), source)

        assertFailsWith<IllegalArgumentException> { LinearPortfolioSearch(descent, foreign) }

        assertFailsWith<IllegalStateException> {
            descent.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> }
        }
    }

    @Test
    fun `weaker execution pins should not erase the retained source query`() {
        val pinnedModel = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0),
                longArrayOf(0),
                Bits(1),
                Bits(1).also { it.set(0) },
            ),
            factors = emptyArray(),
        )
        val cost = LinearObjective(intCoefficients = longArrayOf(1))
        val pinnedRequest = OpenTheoryRequest(pinnedModel, cost)
        val captured = assertNotNull(pinnedRequest.linearAuthorityContext(Cancellation.Never))
        val integer = SourceCoordinate(SourceCoordinate.Kind.Integer, 0)
        val original = SourceProofContext(
            captured.scope.copy(
                condition = SourceCondition(
                    lower = mapOf(integer to BigFraction.ONE),
                    upper = mapOf(integer to BigFraction.ONE),
                ),
            ),
            captured.coordinates,
            captured.premises,
            captured.objective,
            captured.objectiveConstant,
            captured.semanticsChecker,
        )
        val runtime = EngineInvocation(
            pinnedRequest.preparedPlan,
            ModelIdentity.of(pinnedModel, cost),
            sourceAssumptions = Assumptions(ints = mapOf(0 to 1L)),
            executionAssumptions = Assumptions.None,
            scope = original.scope,
        )
        val search = LinearPortfolioSearch(RationalTheoryDescent(pinnedModel, cost, TheoryParams(), original), runtime)

        search.use { it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1) { _ -> } }

        val claim = assertIs<SourceClaim.Optimal>(assertNotNull(search.result?.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ONE), claim.value)
    }

    @Test
    fun `parallel retained arms should publish one checked strict limit through the shared hook`() {
        for (lanes in listOf(1, 2)) {
            val accepted = acceptedTerminals(
                request,
                model,
                objective,
                source,
                binding,
                PortfolioInvocationControls(parallelism = lanes, reseedStaleThreshold = 0),
            )

            assertEquals(1, accepted.size)
            assertIs<SourceClaim.Limit>(assertNotNull(accepted.single().terminal).claim)
        }
    }

    @Test
    fun `a moving integer ray should keep its full direction through shared terminal acceptance`() {
        val mixed = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                longArrayOf(0),
                longArrayOf(0),
                Bits(1),
                Bits(1).also { it.set(0) },
            ),
            factors = arrayOf(
                Linear(
                    intArrayOf(0),
                    doubleArrayOf(-1.0),
                    intArrayOf(0),
                    doubleArrayOf(10.0),
                    LinearOp.GE,
                    0.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val cost = LinearObjective(intCoefficients = longArrayOf(-1))
        val mixedRequest = OpenTheoryRequest(mixed, cost)
        val original = assertNotNull(mixedRequest.linearAuthorityContext(Cancellation.Never))
        val runtime = EngineInvocation(mixedRequest.preparedPlan, ModelIdentity.of(mixed, cost), scope = original.scope)

        val accepted = acceptedTerminals(
            mixedRequest,
            mixed,
            cost,
            original,
            runtime,
            PortfolioInvocationControls(reseedStaleThreshold = 0),
        )

        val ray = assertIs<SourceClaim.Ray>(assertNotNull(accepted.single().terminal).claim)
        val integer = assertNotNull(ray.direction[SourceCoordinate(SourceCoordinate.Kind.Integer, 0)])
        assertTrue(integer > BigFraction.ZERO)
        assertEquals("1", integer.den.toString())
        assertNotNull(ray.direction[SourceCoordinate(SourceCoordinate.Kind.Real, 0)])
    }

    @Test
    fun `a maximized strict limit should keep original units through the shared hook`() {
        val upper = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(
                Linear(
                    intArrayOf(),
                    doubleArrayOf(),
                    intArrayOf(0),
                    doubleArrayOf(1.0),
                    LinearOp.LE,
                    1.0,
                    strict = true,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val maximized = OpenTheoryRequest(upper, objective, maximize = true)
        val original = assertNotNull(maximized.linearAuthorityContext(Cancellation.Never))
        val minimized = assertNotNull(maximized.minimizedObjective)
        val runtime = EngineInvocation(
            maximized.preparedPlan,
            ModelIdentity.of(upper, minimized),
            minimized,
            scope = original.scope,
        )

        val result = acceptedTerminals(maximized, upper, minimized, original, runtime).single()

        val limit = assertIs<SourceClaim.Limit>(assertNotNull(result.terminal).claim)
        assertEquals(SourceValue.Rational(BigFraction.ONE), limit.value)
        assertEquals(ObjectiveSense.MAXIMIZE, result.scope.objective?.sense)
        assertTrue(assertNotNull(result.value) < BigFraction.ONE)
    }

    @Test
    fun `the shared pool should keep the captured non dyadic source objective value`() {
        val third = BigFraction.ofLong(3).reciprocal()
        val real = SourceCoordinate(SourceCoordinate.Kind.Real, 0)
        val exactModel = Problem(
            0,
            0,
            emptyArray(),
            emptyArray(),
            numRealVars = 1,
            realLower = doubleArrayOf(1.0 / 3.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val cost = LinearObjective(realCoefficients = doubleArrayOf(1.0 / 3.0))
        val original = SourceProofContext(
            SourceScope(
                SourceRevision("pool-source", "exact-affine"),
                objective = SourceObjective("cost", ObjectiveSense.MINIMIZE),
            ),
            setOf(real),
            mapOf("lower" to SourcePremise(SourceInequality(mapOf(real to BigFraction.MINUS_ONE), third.negated()))),
            mapOf(real to third),
        )
        val exactRequest = OpenTheoryRequest(exactModel, cost).withLinearAuthority(original)
        val runtime = EngineInvocation(
            exactRequest.preparedPlan,
            ModelIdentity.of(exactModel, cost),
            scope = original.scope,
        )
        val incumbents = PortfolioIncumbents<BigFraction>(
            valueOf = { original.linearValue(it.sample, Cancellation.Never) },
            improves = { candidate, standing -> candidate < standing },
            approximateValue = { value: BigFraction -> value.toDouble() },
            gain = { standing, candidate -> (standing - candidate).toDouble() },
        )

        val accepted = acceptedTerminals(exactRequest, exactModel, cost, original, runtime, incumbents = incumbents)

        assertEquals(BigFraction.ofLong(9).reciprocal(), assertNotNull(incumbents.exchange.current()).objective)
        assertEquals(accepted.single().value, incumbents.exchange.current()?.objective)
    }

    @Test
    fun `a witness only arm should not publish its private linear terminal`() {
        val accepted = ArrayList<LinearOptimizationResult>()
        val factory = PortfolioArmFactory(request.preparedPlan, 0, ArmFamily.LocalSearch) { slot ->
            PortfolioWorker.ofMinimize("repair", 0, resumable = {
                LinearPortfolioSearch(
                    RationalTheoryDescent(
                        model,
                        objective,
                        TheoryParams(cancellation = slot.cancellation),
                        source,
                    ),
                    binding,
                )
            }) { _, _, _, _ -> error("linear search is retained") }
                .bindSource(SourceWorkerBinding(binding, provesResults = false))
        }
        val terminal = LinearPortfolioTerminal(binding, source, Cancellation.Never) { accepted += it }
        val invocation = PortfolioInvocation(
            request.preparedPlan,
            listOf(factory),
            PortfolioInvocationControls(),
            PortfolioEvidence(identity, source.linearWitnessVerifier(Cancellation.Never)).bindInvocation(binding),
            objective,
        )

        val result = invocation.use {
            it.minimize(Cancellation.Never, null, PortfolioIncumbents.exact(objective), terminal)
        }

        assertTrue(accepted.isEmpty())
        assertFalse(result is MinimizeResult.Optimal)
    }

    @Test
    fun `a different prepared invocation should not publish matching source labels`() {
        val foreign = EngineInvocation(model.prepareExecutionPlan(objective), identity, scope = source.scope)
        val factory = PortfolioArmFactory(request.preparedPlan, 0, ArmFamily.Backtrack) { slot ->
            PortfolioWorker.ofMinimize("foreign", 0, resumable = {
                LinearPortfolioSearch(
                    RationalTheoryDescent(
                        model,
                        objective,
                        TheoryParams(cancellation = slot.cancellation),
                        source,
                    ),
                    foreign,
                )
            }) { _, _, _, _ -> error("linear search is retained") }.bindSource(SourceWorkerBinding(foreign))
        }
        assertFailsWith<IllegalArgumentException> {
            PortfolioInvocation(
                request.preparedPlan,
                listOf(factory),
                PortfolioInvocationControls(),
                PortfolioEvidence(identity, source.linearWitnessVerifier(Cancellation.Never)).bindInvocation(binding),
                objective,
            )
        }
    }

    private fun acceptedTerminals(
        request: OpenTheoryRequest,
        model: Problem,
        cost: LinearObjective,
        original: SourceProofContext,
        runtime: EngineInvocation,
        controls: PortfolioInvocationControls = PortfolioInvocationControls(),
        incumbents: PortfolioIncumbents<BigFraction> = PortfolioIncumbents.exact(cost),
    ): List<LinearOptimizationResult> {
        val accepted = ArrayList<LinearOptimizationResult>()
        val factory = PortfolioArmFactory(request.preparedPlan, 0, ArmFamily.Backtrack) { slot ->
            PortfolioWorker.ofMinimize("linear", 0, resumable = {
                LinearPortfolioSearch(
                    RationalTheoryDescent(
                        model,
                        cost,
                        TheoryParams(cancellation = slot.cancellation),
                        original,
                    ),
                    runtime,
                )
            }) { _, _, _, _ -> error("linear search is retained") }.bindSource(SourceWorkerBinding(runtime))
        }
        val terminal = LinearPortfolioTerminal(runtime, original, Cancellation.Never) { accepted += it }
        val invocation = PortfolioInvocation(
            request.preparedPlan,
            listOf(factory),
            controls,
            PortfolioEvidence(ModelIdentity.of(model, cost), original.linearWitnessVerifier(Cancellation.Never))
                .bindInvocation(runtime),
            cost,
        )
        invocation.use { it.minimize(Cancellation.Never, null, incumbents, terminal) }
        return accepted
    }
}
