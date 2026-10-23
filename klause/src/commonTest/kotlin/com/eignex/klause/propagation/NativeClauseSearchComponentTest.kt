package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.solver.search.BooleanSearchResult
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SearchTheoryDecision
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NativeClauseSearchComponentTest {
    @Test
    fun `clause implication should retain its shared reason`() {
        val clauses = NativeClauseSearchComponent(2, listOf(Clause(intArrayOf(0, 2))), Cancellation.Never)
        val session = SearchSession(listOf(clauses))
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))

        assertEquals(true, session.boolValue(1))
        assertEquals(setOf(0, 2), clauses.reasonFor(2)?.literals?.toSet())
    }

    @Test
    fun `retracting to a theory atom should retain earlier clause pins`() {
        val clauses = NativeClauseSearchComponent(3, listOf(Clause(intArrayOf(0, 2, 4))), Cancellation.Never)
        val session = SearchSession(listOf(clauses), atoms = SearchAtomRegistry(3))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        val atom = assertNotNull(session.registerAtom(object : SearchTheoryDecision {}, object : SearchTheoryDecision {}))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(atom.positive)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        session.popTo(2)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(5)))

        assertEquals(false, session.boolValue(0))
        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `theory implication should propagate clauses again after retraction`() {
        val clauses = NativeClauseSearchComponent(3, listOf(Clause(intArrayOf(2, 4))), Cancellation.Never)
        val theory = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                if (decision == SearchDecision.Bool(0)) context.imply(3, SearchExplanation(intArrayOf(1, 3)))
                else ComponentResult.Consistent
        }
        val session = SearchSession(listOf(clauses, theory))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))
        session.popTo(0)
        assertNull(session.boolValue(2))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `shared search should refute incompatible clauses`() {
        val clauses = NativeClauseSearchComponent(2, listOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)),
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 3)),
        ), Cancellation.Never)
        val session = SearchSession(listOf(clauses))
        assertIs<ComponentResult.Consistent>(session.initialize())

        val result = session.solveBoolean(2)

        assertIs<BooleanSearchResult.Exhausted>(result)
    }
}
