package com.eignex.klause.solver.pipeline

import com.eignex.klause.RuntimeInitializedTest
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BigFraction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class FiniteNumericalCompanionsComparisonTest(private val disposition: String) : RuntimeInitializedTest() {
    @Test
    fun `checked repair comparisons should use the current pool instead of the captured center`() {
        val model = Problem(0, 5, Array(5) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, 1, 1, 1), intArrayOf(0, 1, 2, 3, 4), LinearOp.GE, 0),
        ), numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(0.0)).bake()
        val objective = LinearObjective(intCoefficients = longArrayOf(1, 1, 1, 1, 1))
        val plan = model.prepareExecutionPlan(objective, preferFinite = true)
        val params = TheoryParams(sourceIncumbentCompletion = true, maxFlips = 1)
        val cost = finiteNumericalCompanionEligibility(model, PortfolioScenario.sequential(Kind.COP),
            objective, params).requirements.construction
        val center = Sample(BooleanArray(0), LongArray(5) { 1 }, doubleArrayOf(0.0),
            exactReals = listOf(BigFraction.ZERO))

        val events = mutableListOf<SearchEvent.CompanionComparison>()
        lateinit var companions: FiniteNumericalCompanions
        companions = assertNotNull(FiniteNumericalCompanions.prepare(model, objective, params,
            ExecutionControl.Never, cost, incumbentCompletionCheck = { sample, value, _ ->
                if (value < BigFraction.ofLong(5)) {
                    val pool = if (disposition == "dominated") center.copy(ints = LongArray(5)) else sample
                    if (disposition != "improving") companions.incumbents.exchange.offer(
                        pool, objective.evaluateExact(pool))
                }
                true
            }, onComparison = { events += it }).companions)
        companions.incumbents.exchange.offer(center, BigFraction.ofLong(5))

        PortfolioInvocationResources(2, 1, 0, ExecutionControl.Never, null).use { resources ->
            companions.factories(plan, 0).last().materialize(plan, resources.slots[1]).use { worker ->
                assertNotNull(worker.newResumableSearch { 5.0 }).use { search ->
                    repeat(32) {
                        if (!search.isDone && events.none { it.phase == "discovery" })
                            search.runSlice(ExecutionControl.Never, 1_000, 5_000) { }
                    }
                }
            }
        }

        val discovery = events.first { it.phase == "discovery" }
        assertEquals(disposition, discovery.disposition)
        assertEquals(if (disposition == "improving") 1L else 2L, discovery.poolVersion)
        assertEquals(0L, discovery.seedVersion)
        assertEquals("5", discovery.cutoff)
        assertTrue(events.any { it.phase == "external" && it.seedVersion == 1L })
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = listOf("improving", "tied", "dominated").map { arrayOf(it) }
    }
}
