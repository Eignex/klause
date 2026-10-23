package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.search.BooleanSearchResult
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchBoolValue
import com.eignex.klause.solver.search.SearchCandidateHints
import com.eignex.klause.solver.search.SearchComponent
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchExplanation
import com.eignex.klause.solver.search.SearchRealValue
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.solver.search.SourceBoundAtom
import com.eignex.klause.solver.search.SourceBoundTerm
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NativeClauseSearchComponentTest {
    @Test
    fun `shared integer facts should not enter the clause projection`() {
        val clauses = NativeClauseSearchComponent(2, listOf(Clause(intArrayOf(0, 2))), Cancellation.Never)
        val integers = object : SearchComponent {
            override fun initialize(context: SearchContext): ComponentResult {
                context.publish(SearchDecision.IntAtLeast(7, -3))
                return context.publish(SearchDecision.IntAtMost(7, 9))
            }
        }
        val session = SearchSession(listOf(clauses, integers))
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))

        assertEquals(-3L, session.intLowerBound(7))
        assertEquals(9L, session.intUpperBound(7))
        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `native Boolean consequences should expose their clause reasons`() {
        val clauses = NativeClauseSearchComponent(3, listOf(
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(3, 4)),
        ), Cancellation.Never)
        val session = SearchSession(listOf(clauses))
        assertIs<ComponentResult.Consistent>(session.initialize())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(0)))

        assertEquals(true, session.boolValue(2))
        assertEquals(setOf(3, 4), clauses.reasonFor(4)?.literals?.toSet())
    }

    @Test
    fun `named real atoms should retain earlier clause state across siblings`() {
        val clauses = NativeClauseSearchComponent(3, listOf(Clause(intArrayOf(0, 2, 4))), Cancellation.Never)
        val session = SearchSession(listOf(clauses), atoms = SearchAtomRegistry(3))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        val atom = assertNotNull(SourceBoundAtom.rationalSplit(session,
            listOf(SourceBoundTerm(SearchRealValue(0), BigFraction.ONE)), BigFraction.ONE))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Theory(atom.positive)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        session.popTo(2)
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(5)))

        assertEquals(false, session.boolValue(0))
        assertEquals(true, session.boolValue(1))
        assertEquals(true, session.boolValue(atom.positive.literal ushr 1))
        assertNull(clauses.reasonFor(atom.positive.literal))
    }

    @Test
    fun `ignored integer decisions should retract without losing a sibling clause pin`() {
        val clauses = NativeClauseSearchComponent(2, listOf(Clause(intArrayOf(0, 2))), Cancellation.Never)
        val session = SearchSession(listOf(clauses), atoms = SearchAtomRegistry(2))
        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtMost(7, 4)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        session.popTo(1)

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(4L, session.intUpperBound(7))
        assertEquals(true, session.boolValue(0))
        session.popTo(0)
        assertNull(session.intUpperBound(7))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.IntAtLeast(7, 9)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        assertEquals(9L, session.intLowerBound(7))
        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `shared first UIP should resolve native reasons through a theory conflict`() {
        val clauses = NativeClauseSearchComponent(2, listOf(Clause(intArrayOf(1, 2))), Cancellation.Never)
        val theory = object : SearchComponent {
            override fun assert(decision: SearchDecision, context: SearchContext): ComponentResult =
                if (decision == SearchDecision.Bool(2) && context.boolValue(0) == true) {
                    ComponentResult.Conflict(SearchExplanation(intArrayOf(1, 3)))
                }
                else ComponentResult.Consistent
        }
        val session = SearchSession(listOf(clauses, theory))
        assertIs<ComponentResult.Consistent>(session.initialize())

        val result = session.solveBoolean(2, candidateHints = SearchCandidateHints { true })

        val model = assertIs<BooleanSearchResult.Satisfied>(result).model
        assertEquals(false, model.valueOf(SearchBoolValue(0)))
        assertEquals(true, model.valueOf(SearchBoolValue(1)))
        assertEquals(1L, session.learnedClauseStats().assertingConflicts)
    }

    @Test
    fun `native clause conflicts should refute an inconsistent source`() {
        val clauses = NativeClauseSearchComponent(2, listOf(
            Clause(intArrayOf(0, 2)), Clause(intArrayOf(0, 3)),
            Clause(intArrayOf(1, 2)), Clause(intArrayOf(1, 3)),
        ), Cancellation.Never)
        val session = SearchSession(listOf(clauses))
        assertIs<ComponentResult.Consistent>(session.initialize())

        val result = session.solveBoolean(2)

        assertIs<BooleanSearchResult.Exhausted>(result)
    }

    @Test
    fun `cancelled assertion should leave the sibling clause state unmodified`() {
        var cancelled = false
        val stop = Cancellation { cancelled }
        val clauses = NativeClauseSearchComponent(2, listOf(Clause(intArrayOf(0, 2))), stop)
        val session = SearchSession(listOf(clauses), cancellation = stop)
        assertIs<ComponentResult.Consistent>(session.initialize())
        cancelled = true
        assertIs<ComponentResult.Indeterminate>(session.push(SearchDecision.Bool(1)))
        assertNull(session.boolValue(1))
        session.popTo(0)
        cancelled = false

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `interrupted native propagation should leave a complete sibling prefix`() {
        var interrupted = false
        val clauses = NativeClauseSearchComponent(2, listOf(Clause(intArrayOf(0, 2))), Cancellation { interrupted })
        val session = SearchSession(listOf(clauses))
        assertIs<ComponentResult.Consistent>(session.initialize())
        interrupted = true
        assertIs<ComponentResult.Indeterminate>(session.push(SearchDecision.Bool(1)))
        assertNull(session.boolValue(1))
        session.popTo(0)
        interrupted = false

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(0))
        assertEquals(setOf(0, 2), clauses.reasonFor(0)?.literals?.toSet())
    }

    @Test
    fun `interrupted native initialization should not publish a partial root`() {
        val clauses = NativeClauseSearchComponent(1, listOf(Clause(intArrayOf(0))), Cancellation { true })
        val session = SearchSession(listOf(clauses))

        val result = session.initialize()

        assertIs<ComponentResult.Indeterminate>(result)
        assertNull(session.boolValue(0))
    }
}
