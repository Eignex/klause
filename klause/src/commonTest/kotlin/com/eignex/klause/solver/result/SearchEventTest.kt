package com.eignex.klause.solver.result

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.IndomainMin
import com.eignex.klause.solver.backtrack.selector.InputOrder
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SearchEventTest {

    /** Pigeonhole 4 pigeons / 3 holes: UNSAT, needs real search (no level-0 refutation). */
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
    fun `backtrack fires restart events under a tiny luby budget`() {
        val events = mutableListOf<SearchEvent>()
        // The decision cap, not the UNSAT proof, bounds the test: a tiny deterministic Luby
        // schedule redoes near-identical runs and converges only through learned clauses, so
        // proving UNSAT here can take unbounded time. Restart events fire either way. The
        // budget is a single decision per first run — the UNSAT proof needs more than one
        // decision, so at least one restart always fires before it completes.
        BacktrackSolver(pigeonhole()).solve(
            BacktrackParams(
                randomSeed = 1L,
                lubyRestartBase = 1L,
                maxDecisions = 50_000,
                variableHeuristic = InputOrder,
                valueHeuristic = IndomainMin,
                onEvent = { events.add(it) },
            ),
        )
        val restarts = events.filterIsInstance<SearchEvent.Restart>()
        assertTrue(restarts.isNotEmpty(), "expected at least one restart event, got $events")
        // Indices count up from 1 in order.
        assertEquals(List(restarts.size) { (it + 1).toLong() }, restarts.map { it.index })
    }

    @Test
    fun `backtrack fires one incumbent event per improvement`() {
        // 8 independent bools with positive weights: optimum all-false, improvements strict.
        val n = 8
        val problem = Problem(n, 0, emptyArray(), emptyList())
        val events = mutableListOf<SearchEvent>()
        val objective = LinearObjective(boolWeights = LongArray(n) { (it + 1).toLong() })
        val improvements = BacktrackSolver(problem).improvements(
            objective,
            BacktrackParams(
                randomSeed = 1L,
                variableHeuristic = InputOrder,
                valueHeuristic = IndomainMin,
                onEvent = { events.add(it) },
            ),
        ).toList()
        val incumbents = events.filterIsInstance<SearchEvent.Incumbent>()
        assertTrue(incumbents.isNotEmpty(), "expected incumbent events, got $events")
        assertEquals(incumbents.map { it.objective }, incumbents.map { it.objective }.sortedDescending())
        // One event per streamed improvement.
        val streamed = improvements.filterIsInstance<MinimizeResult.BestFound>().size
        assertTrue(incumbents.size >= streamed, "events $incumbents vs streamed $streamed")
    }

    @Test
    fun `local search fires incumbent events while minimizing`() {
        val n = 8
        val problem = Problem(n, 0, emptyArray(), emptyList())
        val events = mutableListOf<SearchEvent>()
        val objective = LinearObjective(boolWeights = LongArray(n) { (it + 1).toLong() })
        LocalSearchSolver(problem).minimize(
            objective,
            LocalSearchParams(
                randomSeed = 1L,
                maxFlips = 20_000,
                onEvent = { events.add(it) },
            ),
        )
        val incumbents = events.filterIsInstance<SearchEvent.Incumbent>()
        assertTrue(incumbents.isNotEmpty(), "expected incumbent events, got $events")
        assertEquals(incumbents.map { it.objective }, incumbents.map { it.objective }.sortedDescending())
    }
}
