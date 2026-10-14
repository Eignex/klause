package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.IncumbentExchange
import com.eignex.klause.solver.incumbent.IncumbentSource
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SearchEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PooledSolutionPhasingTest {

    // Choose ≥ 3 of 8 booleans to minimise their weights: the optimum picks the three cheapest.
    private fun problem() = Problem(
        numBoolVars = 8,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(Cardinality(IntArray(8) { Lit.make(it, true) }, min = 3, max = 8)),
    )

    private val weights = longArrayOf(7L, 3L, 5L, 9L, 1L, 8L, 2L, 6L)
    private val optimum = 6.0 // 1 (var4) + 2 (var6) + 3 (var1)

    // A feasible but deliberately suboptimal peer solution: the first three booleans (cost 15).
    private val pooledHint = Sample(BooleanArray(8) { it < 3 }, LongArray(0))

    /** The shared exchange holding [pooledHint] as the standing incumbent. */
    private fun hintExchange() = IncumbentExchange.minimizing<Sample>().apply { offer(pooledHint, 15.0) }

    @Test
    fun `solution-guided optimization retains its first descent before adopting pooled guidance`() {
        val objectives = ArrayList<Double>()
        val params = BacktrackParams(
            variableSelector = InputOrder,
            valueSelector = SolutionGuided(IndomainMin),
            pooledIncumbents = hintExchange(),
            onEvent = { if (it is SearchEvent.Incumbent) objectives += it.objective },
        )

        BacktrackSolver(problem().bake()).minimize(LinearObjective(boolWeights = weights), params)

        assertEquals(16.0, objectives.first())
    }

    @Test
    fun `LP bounding search reads pooled values before its first local witness`() {
        val objectives = ArrayList<Double>()
        var firstRead: Int? = null
        val exchange = hintExchange()
        val params = BacktrackParams(
            variableSelector = InputOrder,
            valueSelector = SolutionGuided(IndomainMin),
            phaseSaving = true,
            solutionPhasing = true,
            lpPlan = LpPlan(bounding = true, branching = true, rootBudgetMillis = 0L, rootCutHarvest = false),
            pooledIncumbents = IncumbentSource {
                if (firstRead == null) firstRead = objectives.size
                exchange.current()
            },
            onEvent = { if (it is SearchEvent.Incumbent) objectives += it.objective },
        )

        BacktrackSolver(problem().bake()).minimize(LinearObjective(boolWeights = weights), params)

        assertEquals(0, firstRead)
    }

    @Test
    fun `LP value guidance does not admit an infeasible pooled point`() {
        val objective = LinearObjective(boolWeights = weights)
        val params = BacktrackParams(
            valueSelector = SolutionGuided(IndomainMin),
            phaseSaving = true,
            solutionPhasing = true,
            lpPlan = LpPlan(bounding = true, branching = true, rootBudgetMillis = 0L, rootCutHarvest = false),
            pooledIncumbents = IncumbentExchange.minimizing<Sample>().apply {
                offer(Sample(BooleanArray(8), LongArray(0)), 0.0)
            },
        )

        val result = BacktrackSolver(problem().bake()).minimize(objective, params)

        assertEquals(optimum, objective.evaluate(assertNotNull(result.assignment)))
    }

    @Test
    fun `the pooled incumbent is read once per restart`() {
        var polls = 0
        var restarts = 0
        val exchange = hintExchange()
        val params = BacktrackParams(
            solutionPhasing = true,
            lubyRestartBase = 1L,
            randomSeed = 1L,
            pooledIncumbents = IncumbentSource {
                polls++
                exchange.current()
            },
            onEvent = { if (it is SearchEvent.Restart) restarts++ },
        )
        BacktrackSolver(problem().bake()).minimize(LinearObjective(boolWeights = weights), params)
        assertTrue(restarts > 0, "the search must restart for the poll to fire")
        assertEquals(restarts, polls, "the exchange is consulted exactly once per restart")
    }

    @Test
    fun `a suboptimal pooled hint does not compromise the proven optimum`() {
        val objective = LinearObjective(boolWeights = weights)
        val params = BacktrackParams(
            solutionPhasing = true,
            lubyRestartBase = 1L,
            randomSeed = 1L,
            pooledIncumbents = hintExchange(),
        )
        val result = BacktrackSolver(problem().bake()).minimize(objective, params)
        val sample = assertNotNull(result.assignment)
        assertEquals(optimum, objective.evaluate(sample), "phase hints reorder trials, they never cut solutions")
    }
}
