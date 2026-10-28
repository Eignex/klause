package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchAtomRegistry
import com.eignex.klause.solver.search.SearchConflictResolution
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SelectedElementIntegerSourceTest {
    @Test
    fun `a selected cell conflict should retain its exact guard across mapped sibling reuse`() {
        for (offset in listOf(-2, 0, 1)) {
            val native = PropagationSession(Problem(1, 6, arrayOf(IntDomain(0, 6), IntDomain(0, 12),
                IntDomain(0, 12), IntDomain(0, 12), IntDomain(0, 24), IntDomain(offset.toLong(), offset + 1L)), arrayOf(
                    Element(5, 3, longArrayOf(1, 2), true, offset),
                    Linear(intArrayOf(2, 1, -1), intArrayOf(0, 1, 4), LinearOp.LE, 0),
                    Linear(intArrayOf(2, -1), intArrayOf(0, 3), LinearOp.LE, 0),
                )), pbLearning = false).apply { enableIntegerRowLearning() }
            native.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(0, false))), lbd = 3)
            val cp = CpSearchComponent(native, intArrayOf(4, 7, 9, 11, 13, 15))
            val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
            assertIs<ComponentResult.Consistent>(shared.initialize())
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(15, offset.toLong())))
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(13, 7)))
            assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(4, 2)))
            val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
            val state = native.explanationState
            val guard = learned.row.guard
            val atom = state.atomIdOf(Lit.variable(guard))
            assertEquals(AtomKind.EQ, state.atoms.kind[atom])
            assertEquals(5, state.atoms.intVar[atom])
            assertEquals(offset.toLong(), state.atoms.threshold[atom])
            assertEquals(4L, learned.row.coefficient(0))
            assertEquals(-1L, learned.row.coefficient(4))
            val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
            shared.popTo(backjump.decisionLevel)
            assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))
            assertEquals(1L, native.intDomain(0).max)
            shared.popTo(0)
            val retained = assertNotNull(state.learned.integerRows).single()
            native.forgetLearnedClauses { index, _ ->
                native.learnedClausePermanent(index) || state.learned.store[index] === retained
            }
            val late = state.atomVarEq(0, 4)
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(13, 11)))
            assertEquals(5L, native.intDomain(0).max)

            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(15, offset.toLong())))

            assertEquals(2L, native.intDomain(0).max)
            assertSame(retained, state.factorAt(state.intMaxReason[0]))
            assertTrue(assertNotNull(state.reasonOf(state.intMaxAntecedents[0])).contains(guard xor 1))
            assertEquals(false, state.litTruth(Lit.make(late, true)))
            shared.popTo(0)
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtLeast(15, offset + 1L)))
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(13, 4)))
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtLeast(4, 2)))
            assertFalse(state.integerGuardTrueAt(guard))
        }
    }

    @Test
    fun `a selected cell cannot supply an equality before its historical selection`() {
        val state = PropagationState(Problem(0, 4, Array(3) { IntDomain(0, 6) } + IntDomain(0, 1),
            arrayOf(Element(3, 2, longArrayOf(0, 1), true, 0))), Assumptions.None)
        state.undoLogging = true
        val before = state.undo.size
        assertTrue(state.setIntMaxAsDecision(3, 0))

        assertNull(GuardedIntegerSource(state).source(0, before))
        val row = assertNotNull(assertNotNull(GuardedIntegerSource(state).source(0)).row(true))
        assertFalse(state.integerGuardTrueAt(row.guard, before))
        assertTrue(state.integerGuardTrueAt(row.guard))
    }

    @Test
    fun `a repeated cell should retain the exact selected index identity`() {
        val native = PropagationSession(Problem(0, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 6), IntDomain(0, 1)),
            arrayOf(Element(2, 1, longArrayOf(0, 0), true, 0))))
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 0))
        val state = native.explanationState
        val first = assertNotNull(assertNotNull(GuardedIntegerSource(state).source(0)).row(true))
        native.popToLevel(0)
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(2, 1))

        val second = assertNotNull(assertNotNull(GuardedIntegerSource(state).source(0)).row(true))

        assertEquals(first.coefficient(0), second.coefficient(0))
        assertFalse(first.guard == second.guard)
        assertFalse(state.integerGuardTrueAt(first.guard))
        assertTrue(state.integerGuardTrueAt(second.guard))
    }

    @Test
    fun `a selected result alias should withhold a vacuous arithmetic row`() {
        val native = PropagationSession(Problem(0, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 6), IntDomain(0, 1)),
            arrayOf(Element(2, 1, longArrayOf(1, 0), true, 0))))
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 0))

        val source = assertNotNull(GuardedIntegerSource(native.explanationState).source(0))

        assertNull(source.row(true))
        assertNull(source.row(false))
    }

    @Test
    fun `a selected support reason should withhold numerical endpoint admission`() {
        val native = PropagationSession(Problem(0, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 6), IntDomain(0, 1)),
            arrayOf(Element(2, 1, longArrayOf(0, 0), true, 0))))
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 0))

        val source = GuardedIntegerSource(native.explanationState).source(0, forBoundReason = true)

        assertNull(source)
    }

    @Test
    fun `selected support failures caused by a hole should retain clause fallback`() {
        val native = PropagationSession(Problem(0, 4, arrayOf(IntDomain(0, 6).excludeValue(4),
            IntDomain(0, 6), IntDomain(0, 6), IntDomain(0, 1)),
            arrayOf(Element(3, 2, longArrayOf(0, 1), true, 0)))).apply { enableIntegerRowLearning() }
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(3, 0))

        val conflict = assertIs<PropagationResult.Unsat>(native.pinInt(2, 4))

        assertFalse(conflict.learnedClause is ConflictAnalyzer.AnalysisResult.LearnedIntegerRow)
        assertTrue(native.explanationState.learned.integerRows.isNullOrEmpty())
    }
}
