package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.PresolveBudget
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BooleanProbeCandidatesTest {
    @Test
    fun `unused columns should not construct a probe root`() {
        val source = Problem(16, 0, emptyArray(), emptyList())
        for (problem in listOf(source, source.bake())) {
            val budget = PresolveBudget(Long.MAX_VALUE)

            val graph = problem.propagatedImplicationGraph(4, budget.orSpent(Cancellation.Never))

            assertTrue(graph.all { it.isEmpty() })
            assertEquals(0L, budget.rootConstructionEntries)
        }
    }

    @Test
    fun `unused and root fixed columns should leave the cap for live candidates`() {
        val source = Problem(
            8, 0, emptyArray(),
            listOf(
                Clause(intArrayOf(Lit.make(4, true))),
                Clause(intArrayOf(Lit.make(4, false), Lit.make(5, true))),
                Clause(intArrayOf(Lit.make(6, false), Lit.make(7, true))),
            ),
        )
        for (problem in listOf(source, source.bake())) {
            val graph = problem.propagatedImplicationGraph(1)

            assertTrue(graph[Lit.make(4, true)].isEmpty())
            assertTrue(graph[Lit.make(5, true)].isEmpty())
            assertTrue(Lit.make(7, true) in graph[Lit.make(6, true)])
        }
    }
}
