package com.eignex.klause.solver.search

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceClauseBooleanBranchingTest {
    @Test
    fun `source clauses should precede unrelated Boolean variables`() {
        for (positive in listOf(false, true)) {
            val component = ClauseSearchComponent(listOf(
                Clause(intArrayOf(Lit.make(2, positive), Lit.make(3, true))),
            ))
            val branching = assertNotNull(component.booleanBranching(5))
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(5))
            session.openRun(numBoolVars = 5, booleanBranching = branching)
            assertIs<ComponentResult.Consistent>(session.initialize())

            val choices = branching.alternatives(session)

            assertEquals(listOf(SearchDecision.Bool(Lit.make(2, positive)),
                SearchDecision.Bool(Lit.make(2, !positive))), choices)
            assertNull(session.boolValue(2))
            assertEquals(choices, branching.alternatives(session))
        }
    }

    @Test
    fun `retraction should restore an earlier source clause after the cursor advances`() {
        for (restart in listOf(false, true)) {
            val component = ClauseSearchComponent(listOf(
                Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
                Clause(intArrayOf(Lit.make(4, false), Lit.make(5, true))),
            ))
            var satisfied = 0
            val branching = assertNotNull(component.booleanBranching(7) { satisfied++ })
            val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(7))
            session.openRun(numBoolVars = 7, booleanBranching = branching)
            assertIs<ComponentResult.Consistent>(session.initialize())
            val initial = branching.alternatives(session)
            assertEquals(SearchDecision.Bool(Lit.make(2, true)), initial?.first())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(2, true))))
            assertEquals(SearchDecision.Bool(Lit.make(4, false)), branching.alternatives(session)?.first())
            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(4, false))))
            assertEquals(BooleanBranching.SourceOrder(7).alternatives(session), branching.alternatives(session))
            assertEquals(1, satisfied)

            if (restart) assertIs<ComponentResult.Consistent>(session.restart()) else session.popTo(0)

            assertEquals(initial, branching.alternatives(session))
        }
    }

    @Test
    fun `clause focused choices should retain the same hinted models through learned backjumps`() {
        for (preferred in listOf(false, true)) {
            val observations = ArrayList<Set<List<Boolean?>>>()
            for (focused in listOf(false, true)) {
                val component = ClauseSearchComponent(listOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(2, false))),
                ))
                val branching = if (focused) assertNotNull(component.booleanBranching(4))
                else BooleanBranching.SourceOrder(4)
                val session = SearchSession(listOf(component), atoms = SearchAtomRegistry(4))
                assertIs<ComponentResult.Consistent>(session.initialize())
                val run = session.openRun(numBoolVars = 4, booleanBranching = branching,
                    candidateHints = SearchCandidateHints { preferred })
                val models = HashSet<List<Boolean?>>()
                var event = run.next()
                while (event is SearchRunEvent.Satisfied) {
                    models += List(4, session::boolValue)
                    event = run.next()
                }
                assertEquals(SearchRunEvent.Exhausted, event)
                assertEquals(8, models.size)
                observations += models
            }

            assertEquals(observations[0], observations[1])
        }
    }

    @Test
    fun `a stopped clause scan should preserve fallback choices`() {
        var armed = false
        var stopped = false
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = stopped
            override fun workMeter(): WorkMeter = WorkMeter { if (armed) stopped = true }
        }
        val component = ClauseSearchComponent(listOf(
            Clause(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
        ))
        var satisfied = 0
        val branching = assertNotNull(component.booleanBranching(4) { satisfied++ })
        val session = SearchSession(listOf(component), cancellation = cancellation, atoms = SearchAtomRegistry(4))
        session.openRun(numBoolVars = 4, booleanBranching = branching)
        assertIs<ComponentResult.Consistent>(session.initialize())
        armed = true

        val choices = branching.alternatives(session)

        assertEquals(BooleanBranching.SourceOrder(4).alternatives(session), choices)
        assertEquals(0, satisfied)
        assertNull(session.boolValue(2))
        assertEquals(true, stopped)
    }
}
