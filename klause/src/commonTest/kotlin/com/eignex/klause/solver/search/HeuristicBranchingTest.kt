package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class HeuristicBranchingTest {

    @Test
    fun `the first restart should switch Boolean order within the same decision budget`() {
        val session = SearchSession(emptyList())
        assertIs<ComponentResult.Consistent>(session.initialize())
        val branching = ActivityRestartBooleanBranching(numBoolVars = 4, initialDecisions = 2L)
        val variables = mutableListOf<Int>()
        val restarts = mutableListOf<Long>()
        val observer = object : SearchRunObserver by branching {
            override fun onRestart(decisions: Long) {
                restarts += decisions
            }

            override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                variables += (decision as SearchDecision.Bool).literal ushr 1
            }
        }
        val run = session.openRun(4, SearchSolveParams(maxDecisions = 4L, restart = branching),
            booleanBranching = branching, observer = observer)

        val result = run.next()

        assertEquals(listOf(2L), restarts)
        assertEquals(listOf(0, 1, 0, 3), variables)
        assertIs<SearchRunEvent.Indeterminate.Budget>(result)
    }

    @Test
    fun `heuristic branching should offer the false polarity before the true one`() {
        val session = SearchSession(emptyList())
        val branching = HeuristicBooleanBranching(Vsids(), numBoolVars = 1)

        val split = branching.alternatives(session)

        assertEquals(listOf(SearchDecision.Bool(1), SearchDecision.Bool(0)), split)
    }

    @Test
    fun `heuristic branching should split the variable an asserting clause implicated`() {
        val session = SearchSession(emptyList())
        val branching = HeuristicBooleanBranching(Vsids(), numBoolVars = 4)
        branching.alternatives(session)

        branching.onConflict(null)
        branching.onLearnedConflict(asserting(intArrayOf(3 shl 1)))

        assertEquals(listOf(3, 3), branching.splitVariables(session))
    }

    @Test
    fun `heuristic branching should split the failed decision variable when no clause was learned`() {
        val session = SearchSession(emptyList())
        val branching = HeuristicBooleanBranching(Vsids(), numBoolVars = 4)
        branching.alternatives(session)

        branching.onConflict(SearchDecision.Bool(2 shl 1))
        branching.onConflict(null)

        assertEquals(listOf(2, 2), branching.splitVariables(session))
    }

    @Test
    fun `heuristic branching should re-offer a freed variable ahead of one no conflict touched`() {
        val session = SearchSession(emptyList())
        val branching = HeuristicBooleanBranching(Vsids(), numBoolVars = 4)
        session.openRun(numBoolVars = 4, booleanBranching = branching)
        branching.alternatives(session)
        branching.onConflict(null)
        branching.onLearnedConflict(asserting(intArrayOf(3 shl 1)))
        assertEquals(listOf(3, 3), branching.splitVariables(session))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3 shl 1)))

        session.popTo(0)

        assertEquals(listOf(3, 3), branching.splitVariables(session))
    }

    @Test
    fun `heuristic branching should report no split once every Boolean is assigned`() {
        val session = SearchSession(emptyList())
        val branching = HeuristicBooleanBranching(Vsids(), numBoolVars = 1)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

        assertNull(branching.alternatives(session))
    }

    @Test
    fun `heuristic branching should find a model when the first branch conflicts`() {
        val session = SearchSession(
            listOf(ClauseSearchComponent(listOf(Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3))))),
        )
        val branching = HeuristicBooleanBranching(Vsids(), numBoolVars = 3)
        assertIs<ComponentResult.Consistent>(session.initialize())

        val result = session.solve(numBoolVars = 3, booleanBranching = branching, observer = branching)

        assertIs<SearchResult.Satisfied>(result)
        assertEquals(true, session.boolValue(0))
    }

    private fun HeuristicBooleanBranching.splitVariables(context: SearchContext): List<Int>? =
        alternatives(context)?.map { (it as SearchDecision.Bool).literal ushr 1 }

    private fun asserting(literals: IntArray): SearchLearnedConflict = object : SearchLearnedConflict {
        override val decisionLevel: Int = 0
        override val lbd: Int = 1
        override val guardLiterals: IntArray = literals
        override val decisionLevels: IntArray = IntArray(0)
        override fun apply(session: SearchSession): SearchLearnedConflictResult = SearchLearnedConflictResult.Resume
    }
}
