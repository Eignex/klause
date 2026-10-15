package com.eignex.klause.solver.result

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchEventTest {

    private fun pigeonhole(): Problem {
        val pigeons = 4
        val holes = 3
        fun v(p: Int, h: Int) = p * holes + h
        val factors = buildList {
            for (p in 0 until pigeons) {
                add(Cardinality.atLeastOne(IntArray(holes) { h -> Lit.make(v(p, h), true) }))
            }
            for (h in 0 until holes) {
                add(Cardinality.atMostOne(IntArray(pigeons) { p -> Lit.make(v(p, h), true) }))
            }
        }
        return Problem(pigeons * holes, 0, emptyArray(), factors)
    }

    @Test
    fun `backtrack should emit indexed restart events under a unit Luby base`() {
        val events = mutableListOf<SearchEvent>()
        // The decision cap bounds the run; a unit Luby base may need very long to prove UNSAT.
        BacktrackSolver(pigeonhole().bake()).solve(
            BacktrackParams(
                randomSeed = 1L,
                lubyRestartBase = 1L,
                maxDecisions = 50_000,
                variableSelector = InputOrder,
                valueSelector = IndomainMin,
                onEvent = { events.add(it) },
            ),
        )
        val restarts = events.filterIsInstance<SearchEvent.Restart>()
        assertTrue(restarts.isNotEmpty(), "got $events")
        assertEquals(List(restarts.size) { (it + 1).toLong() }, restarts.map { it.index })
    }

    @Test
    fun `backtrack should emit an incumbent event per improvement`() {
        val n = 8
        val problem = Problem(n, 0, emptyArray(), emptyList())
        val events = mutableListOf<SearchEvent>()
        val objective = LinearObjective(boolWeights = LongArray(n) { (it + 1).toLong() })
        val improvements = BacktrackSolver(problem.bake()).improvements(
            objective,
            BacktrackParams(
                randomSeed = 1L,
                variableSelector = InputOrder,
                valueSelector = IndomainMin,
                onEvent = { events.add(it) },
            ),
        ).toList()
        val incumbents = events.filterIsInstance<SearchEvent.Incumbent>()
        assertTrue(incumbents.isNotEmpty(), "got $events")
        assertEquals(incumbents.map { it.objective }, incumbents.map { it.objective }.sortedDescending())
        val streamed = improvements.filterIsInstance<MinimizeResult.BestFound>().size
        assertTrue(incumbents.size >= streamed, "events $incumbents vs streamed $streamed")
    }

    @Test
    fun `local search should emit improving incumbent events while minimizing`() {
        val n = 8
        val problem = Problem(n, 0, emptyArray(), emptyList())
        val events = mutableListOf<SearchEvent>()
        val objective = LinearObjective(boolWeights = LongArray(n) { (it + 1).toLong() })
        LocalSearchSolver(problem.bake()).minimize(
            objective,
            LocalSearchParams(
                randomSeed = 1L,
                maxFlips = 20_000,
                onEvent = { events.add(it) },
            ),
        )
        val incumbents = events.filterIsInstance<SearchEvent.Incumbent>()
        assertTrue(incumbents.isNotEmpty(), "got $events")
        assertEquals(incumbents.map { it.objective }, incumbents.map { it.objective }.sortedDescending())
    }
}
