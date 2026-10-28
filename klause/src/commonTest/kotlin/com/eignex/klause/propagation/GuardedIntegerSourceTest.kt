package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.arithmetic.ReifiedLinear
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

class GuardedIntegerSourceTest {
    @Test
    fun `a guard pinned after the historical boundary should withhold the source row`() {
        val state = PropagationState(Problem(1, 1, arrayOf(IntDomain(0, 6)), arrayOf(
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 3),
        )), Assumptions.None)
        state.undoLogging = true
        val before = state.undo.size
        assertTrue(state.pinBool(0, true))

        assertNull(GuardedIntegerSource(state).source(0, before))
        assertEquals(Lit.make(0, true), assertNotNull(GuardedIntegerSource(state).source(0)).guard)
    }

    @Test
    fun `distinct signed guards should withhold arithmetic resolution`() {
        for (right in listOf(Lit.make(0, false), Lit.make(1, true))) {
            val left = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(0, 1),
                longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 3), true, Lit.make(0, true)))
            val other = assertNotNull(IntegerConflictRow.source(LinearPropagator(intArrayOf(), intArrayOf(1, 2),
                longArrayOf(-1, 1), intArrayOf(1, 2), LinearOp.LE, 3), true, right))

            assertNull(left.resolve(other, 1))
        }
    }

    @Test
    fun `a signed reified row should wake at a sibling endpoint after guard undo`() {
        for (truth in listOf(true, false)) {
            val native = PropagationSession(Problem(1, 3,
                arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                    ReifiedLinear(0, intArrayOf(2, 1, -1), intArrayOf(0, 1, 2),
                        if (truth) LinearOp.LE else LinearOp.GE, if (truth) 0 else 1),
                    Linear(intArrayOf(2, -1), intArrayOf(0, 1), LinearOp.LE, 0),
                )), pbLearning = false).apply { enableIntegerRowLearning() }
            val cp = CpSearchComponent(native, intArrayOf(4, 7, 9))
            val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(1))
            val guard = Lit.make(0, truth)
            assertIs<ComponentResult.Consistent>(shared.initialize())
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.Bool(guard)))
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(9, 7)))
            assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(4, 2)))
            val learned = assertIs<ConflictAnalyzer.AnalysisResult.LearnedIntegerRow>(cp.lastConflict?.learnedClause)
            assertEquals(guard, learned.row.guard)
            assertEquals(4L, learned.row.coefficient(0))
            assertEquals(-1L, learned.row.coefficient(2))
            val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
            shared.popTo(backjump.decisionLevel)
            assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))
            assertEquals(1L, native.intDomain(0).max)
            shared.popTo(0)
            val state = native.explanationState
            val retained = assertNotNull(state.learned.integerRows).single()
            native.forgetLearnedClauses { index, _ -> state.learned.store[index] === retained }
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(9, 11)))
            assertEquals(6L, native.intDomain(0).max)

            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.Bool(guard)))

            assertEquals(2L, native.intDomain(0).max)
            assertSame(retained, state.factorAt(state.intMaxReason[0]))
            assertTrue(assertNotNull(state.reasonOf(state.intMaxAntecedents[0])).contains(guard xor 1))
            shared.popTo(0)
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.Bool(guard xor 1)))
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(9, 0)))
            assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtLeast(4, 2)))
            assertTrue(native.learnedConstraintSatisfied(learned))
            assertFalse(state.integerGuardTrueAt(guard))
        }
    }
}
