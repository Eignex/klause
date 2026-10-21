package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SourceOrderBooleanBranchingTest {
    @Test
    fun `retraction should restore source order after skipping propagated variables`() {
        val session = SearchSession(listOf(ClauseSearchComponent(listOf(
            Clause(intArrayOf(Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
        ))), atoms = SearchAtomRegistry(5))
        val branching = SourceOrderBooleanBranching(5)
        val sourceOrder = BooleanBranching.SourceOrder(5)
        session.openRun(numBoolVars = 5, booleanBranching = branching)
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertEquals(sourceOrder.alternatives(session), branching.alternatives(session))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        val choices = sourceOrder.alternatives(session)
        assertEquals(choices, branching.alternatives(session))
        assertEquals(choices, branching.alternatives(session))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(3, true))))
        assertEquals(sourceOrder.alternatives(session), branching.alternatives(session))

        session.popTo(0)

        assertEquals(sourceOrder.alternatives(session), branching.alternatives(session))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, false))))
        assertEquals(sourceOrder.alternatives(session), branching.alternatives(session))
        assertIs<ComponentResult.Consistent>(session.restart())
        assertEquals(sourceOrder.alternatives(session), branching.alternatives(session))
    }

    @Test
    fun `source order should enumerate the same hinted models through learned backjumps`() {
        for (preferred in listOf(false, true)) {
            val observations = ArrayList<List<List<Boolean?>>>()
            for (branching in listOf(BooleanBranching.SourceOrder(4), SourceOrderBooleanBranching(4))) {
                val session = SearchSession(listOf(ClauseSearchComponent(listOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(2, false))),
                ))), atoms = SearchAtomRegistry(4))
                assertIs<ComponentResult.Consistent>(session.initialize())
                val run = session.openRun(numBoolVars = 4, booleanBranching = branching,
                    candidateHints = SearchCandidateHints { preferred })
                val models = ArrayList<List<Boolean?>>()
                var event = run.next()
                while (event is SearchRunEvent.Satisfied) {
                    models += List(4, session::boolValue)
                    event = run.next()
                }
                assertEquals(SearchRunEvent.Exhausted, event)
                assertEquals(8, models.distinct().size)
                observations += models
            }

            assertEquals(observations[0], observations[1])
        }
    }

    @Test
    fun `an exhausted source cursor should start from the beginning in another session`() {
        val branching = SourceOrderBooleanBranching(1)
        val first = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        first.openRun(numBoolVars = 1, booleanBranching = branching)
        assertIs<ComponentResult.Consistent>(first.push(SearchDecision.Bool(Lit.make(0, true))))
        assertNull(branching.alternatives(first))
        val second = SearchSession(emptyList(), atoms = SearchAtomRegistry(1))
        second.openRun(numBoolVars = 1, booleanBranching = branching)

        val choices = branching.alternatives(second)

        assertEquals(listOf(SearchDecision.Bool(Lit.make(0, false)), SearchDecision.Bool(Lit.make(0, true))), choices)
    }
}
