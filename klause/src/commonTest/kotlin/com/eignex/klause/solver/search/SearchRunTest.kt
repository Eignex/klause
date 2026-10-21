package com.eignex.klause.solver.search

import com.eignex.klause.ir.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SearchRunTest {
    @Test
    fun `an indeterminate alternative should leave its sibling available`() {
        val component = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                if (context.boolValue(0) == false) ComponentResult.Indeterminate else ComponentResult.Consistent
        }
        val session = SearchSession(listOf(component))
        session.initialize()
        val run = session.openRun(numBoolVars = 1)

        val result = assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
    }

    @Test
    fun `a learned backjump should preserve nested conditions`() {
        for (selection in listOf(
            SearchBranchSelection.Default,
            SearchBranchSelection.Priority(
                listOf(SearchBranchKind.Arithmetic, SearchBranchKind.Finite, SearchBranchKind.Boolean),
            ),
        )) {
            val session = SearchSession(
                listOf(object : SearchComponent {
                    override fun check(context: SearchContext): ComponentCheck =
                        if (context.boolValue(0) == true && context.boolValue(1) == false) {
                            ComponentCheck.Infeasible(
                                SearchExplanation(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
                            )
                        } else {
                            ComponentCheck.Feasible
                        }
                }),
            )
            session.initialize()
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            session.push(SearchDecision.Bool(Lit.make(2, true)))
            val run = session.openConditionedRun(3, branchSelection = selection)

            val result = assertIs<SearchRunEvent.Satisfied>(run.next())

            assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
            assertEquals(true, result.model.valueOf(SearchBoolValue(1)))
            assertEquals(true, result.model.valueOf(SearchBoolValue(2)))
        }
    }

    @Test
    fun `restart should preserve the enclosing condition`() {
        for (selection in listOf(
            SearchBranchSelection.Default,
            SearchBranchSelection.Priority(
                listOf(SearchBranchKind.Arithmetic, SearchBranchKind.Finite, SearchBranchKind.Boolean),
            ),
        )) {
            val session = SearchSession(
                listOf(object : SearchComponent {
                    override fun check(context: SearchContext): ComponentCheck = if (context.boolValue(1) == false) {
                        ComponentCheck.Infeasible(SearchExplanation(intArrayOf(Lit.make(1, true))))
                    } else {
                        ComponentCheck.Feasible
                    }
                }),
            )
            session.initialize()
            session.push(SearchDecision.Bool(Lit.make(0, true)))
            val run = session.openConditionedRun(
                2,
                SearchSolveParams(maxDecisions = 4, restart = SearchRestart.Every(1)),
                branchSelection = selection,
            )

            val result = assertIs<SearchRunEvent.Satisfied>(run.next())

            assertEquals(true, result.model.valueOf(SearchBoolValue(0)))
            assertEquals(true, result.model.valueOf(SearchBoolValue(1)))
            assertEquals(1L, session.learnedClauseStats().restarts)
        }
    }
}
