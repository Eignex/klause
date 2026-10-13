package com.eignex.klause.solver.search

import com.eignex.klause.ir.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SearchRunTest {
    @Test
    fun `source order consults optional early splits while custom Boolean order keeps priority`() {
        for (custom in listOf(false, true)) {
            val session = SearchSession(emptyList())
            session.useEarlyBrancher { context ->
                if (context.boolValue(1) == null) {
                    listOf(SearchDecision.Bool(Lit.make(1, false)), SearchDecision.Bool(Lit.make(1, true)))
                } else {
                    null
                }
            }
            val committed = ArrayList<SearchDecision>()
            val observer = object : SearchRunObserver {
                override fun onCommit(decision: SearchDecision, decisionLevel: Int) {
                    committed += decision
                }
            }
            val sourceOrder = BooleanBranching.SourceOrder(2)
            val branching = if (custom) BooleanBranching(sourceOrder::alternatives) else sourceOrder
            assertIs<ComponentResult.Consistent>(session.initialize())

            assertIs<SearchResult.Satisfied>(session.solve(2, booleanBranching = branching, observer = observer))

            assertEquals(SearchDecision.Bool(Lit.make(if (custom) 0 else 1, false)), committed.first())
        }
    }

    @Test
    fun `declined early splits preserve ordinary Boolean traversal`() {
        val session = SearchSession(emptyList())
        session.useEarlyBrancher { null }
        assertIs<ComponentResult.Consistent>(session.initialize())

        val result = assertIs<SearchResult.Satisfied>(session.solve(2))

        assertEquals(false, result.model.valueOf(SearchBoolValue(0)))
        assertEquals(false, result.model.valueOf(SearchBoolValue(1)))
    }

    @Test
    fun `an exhausted decision allowance skips optional early selection`() {
        val session = SearchSession(emptyList())
        session.useEarlyBrancher { error("no decision allowance") }
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<SearchResult.Indeterminate>(session.solve(1, SearchSolveParams(maxDecisions = 0)))
    }

    @Test
    fun `activity and hinted Boolean selectors keep priority over optional early splits`() {
        for (hinted in listOf(false, true)) {
            val session = SearchSession(emptyList())
            session.useEarlyBrancher { error("custom Boolean policy") }
            val branching = if (hinted) BooleanBranching.SourceOrder(2) else HeuristicBooleanBranching(Vsids(), 2)
            val hints = if (hinted) SearchCandidateHints { true } else SearchCandidateHints.None
            assertIs<ComponentResult.Consistent>(session.initialize())

            val result = assertIs<SearchResult.Satisfied>(session.solve(2,
                candidateHints = hints, booleanBranching = branching))

            assertEquals(hinted, result.model.valueOf(SearchBoolValue(0)))
            assertEquals(hinted, result.model.valueOf(SearchBoolValue(1)))
        }
    }

    @Test
    fun `a learned backjump preserves nested conditions`() {
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck =
                if (context.boolValue(0) == true && context.boolValue(1) == false) {
                    ComponentCheck.Infeasible(SearchExplanation(intArrayOf(Lit.make(0, false), Lit.make(1, true))))
                } else {
                    ComponentCheck.Feasible
                }
        }))
        session.initialize()
        session.push(SearchDecision.Bool(Lit.make(0, true)))
        session.push(SearchDecision.Bool(Lit.make(2, true)))
        val run = session.openConditionedRun(3)

        val result = assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
        assertEquals(true, result.model.valueOf(SearchBoolValue(1)))
        assertEquals(true, result.model.valueOf(SearchBoolValue(2)))
    }

    @Test
    fun `restart preserves the enclosing condition`() {
        val session = SearchSession(listOf(object : SearchComponent {
            override fun check(context: SearchContext): ComponentCheck = if (context.boolValue(1) == false) {
                ComponentCheck.Infeasible(SearchExplanation(intArrayOf(Lit.make(1, true))))
            } else {
                ComponentCheck.Feasible
            }
        }))
        session.initialize()
        session.push(SearchDecision.Bool(Lit.make(0, true)))
        val run = session.openConditionedRun(2, SearchSolveParams(maxDecisions = 4, restart = SearchRestart.Every(1)))

        val result = assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
        assertEquals(true, result.model.valueOf(SearchBoolValue(1)))
        assertEquals(1L, session.learnedClauseStats().restarts)
    }
}
