package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchResult
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CpSearchComponentTest {

    @Test
    fun `shared runner enumerates CP branches without repeating a model`() {
        val component = CpSearchComponent(
            PropagationSession(Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray())),
        )
        component.rebase()
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.initialize())
        val run = session.openRun(0)
        val first = assertIs<SearchRunEvent.Satisfied>(run.next())
        val second = assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(
            setOf(0L, 1L),
            setOf(
                first.model.valueOf<Long>(com.eignex.klause.solver.search.SearchIntValue(0)),
                second.model.valueOf<Long>(com.eignex.klause.solver.search.SearchIntValue(0)),
            ),
        )
        assertIs<SearchRunEvent.Exhausted>(run.next())
    }

    @Test
    fun `CP explains a Boolean it published when the shared analyzer asks`() {
        val propagation = PropagationSession(
            Problem(
                2,
                0,
                emptyArray(),
                arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
            ),
        )
        val component = CpSearchComponent(propagation)
        val session = SearchSession(listOf(component))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

        val reason = component.reasonFor(Lit.make(1, true))

        assertEquals(
            setOf(Lit.make(1, true), Lit.make(0, false)),
            reason?.literals?.toSet(),
        )
    }

    @Test
    fun `shared trail retraction rewinds the CP component`() {
        val propagation = PropagationSession(
            Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true))))),
        )
        val session = SearchSession(listOf(CpSearchComponent(propagation)))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))
        session.popTo(0)

        assertEquals(0, propagation.decisionLevel)
    }

    @Test
    fun `CP Boolean propagation is visible to peer components`() {
        val propagation = PropagationSession(
            Problem(
                2,
                0,
                emptyArray(),
                arrayOf(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
            ),
        )
        val session = SearchSession(listOf(CpSearchComponent(propagation)))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, true))))

        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `CP root Boolean propagation is published during shared initialization`() {
        val propagation = PropagationSession(
            Problem(1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true))))),
        )
        val session = SearchSession(listOf(CpSearchComponent(propagation)))

        assertIs<ComponentResult.Consistent>(session.initialize())

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `shared integer bound decision retracts to the rebased CP root`() {
        val propagation = PropagationSession(
            Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray()),
        )
        val component = CpSearchComponent(propagation)
        component.rebase()
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtMost(0, 4)))
        assertEquals(1, propagation.decisionLevel)
        assertEquals(4L, propagation.intDomain(0).max)
        session.popTo(0)

        assertEquals(0, propagation.decisionLevel)
        assertEquals(10L, propagation.intDomain(0).max)
    }

    @Test
    fun `CP component maps shared source integer decisions to its compact projection`() {
        val propagation = PropagationSession(
            Problem(0, 1, arrayOf(IntDomain(0, 10)), emptyArray()),
        )
        val component = CpSearchComponent(propagation, intArrayOf(4))
        component.rebase()
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtMost(4, 4)))

        assertEquals(4L, propagation.intDomain(0).max)
    }

    @Test
    fun `native learned implication is shared without a second CP pin`() {
        val propagation = PropagationSession(Problem(1, 0, emptyArray(), emptyArray()))
        val component = CpSearchComponent(propagation)
        component.rebase()
        var observed: Boolean? = null
        val peer = object : com.eignex.klause.solver.search.SearchComponent {
            override fun assert(
                decision: SearchDecision,
                context: com.eignex.klause.solver.search.SearchContext,
            ): ComponentResult {
                observed = context.boolValue(0)
                return ComponentResult.Consistent
            }
        }
        val session = SearchSession(listOf(component, peer))

        assertIs<ComponentResult.Consistent>(session.initialize())
        val learned = assertIs<PropagationResult.Implied>(
            propagation.addLearnedClause(Clause(intArrayOf(Lit.make(0, true))), lbd = 1),
        )
        assertIs<ComponentResult.Consistent>(component.import(learned, session))
        assertIs<ComponentResult.Consistent>(session.propagate())

        assertEquals(true, observed)
        assertEquals(0, propagation.decisionLevel)
    }

    @Test
    fun `a Boolean CP conflict is not duplicated into the shared database`() {
        val propagation = PropagationSession(
            Problem(
                2,
                0,
                emptyArray(),
                arrayOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                ),
            ),
        )
        val session = SearchSession(listOf(CpSearchComponent(propagation)))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<SearchResult.Satisfied>(session.solve(1))

        assertEquals(0, session.learnedClauseCount, "CP keeps the clause in its own database")
        assertEquals(true, session.boolValue(0), "its consequence still reaches the shared session")
    }
}
