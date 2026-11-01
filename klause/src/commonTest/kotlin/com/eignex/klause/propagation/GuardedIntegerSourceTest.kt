package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.simplex.exact.BigFraction
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
    fun `sources beyond the bounded view limit should retain clause fallback`() {
        val state = PropagationState(Problem(0, 33, Array(33) { IntDomain(0, 1) }, arrayOf(
            Linear(IntArray(33) { 1 }, IntArray(33) { it }, LinearOp.LE, 16),
        )), Assumptions.None)

        assertNull(GuardedIntegerSource(state).source(0, sourceWork = IntegerSourceWork(state, remaining = 0)))
    }

    @Test
    fun `wide reified rows should retain fallback with source cancellation enabled`() {
        val state = PropagationState(Problem(1, 17, Array(17) { IntDomain(0, 1) }, arrayOf(
            ReifiedLinear(0, IntArray(17) { 1 }, IntArray(17) { it }, LinearOp.LE, 8),
        )), Assumptions.None)
        assertTrue(state.pinBool(0, true))

        assertNull(GuardedIntegerSource(state).source(0, sourceWork = IntegerSourceWork(state)))
    }

    @Test
    fun `compact exact integer sources should derive a mapped consumed row`() {
        val native = PropagationSession(Problem(0, 3,
            arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                ExactLinear.of(IntArray(3) { Term.ofIntVar(it) },
                    listOf(BigFraction.ofLong(2), BigFraction.ONE, BigFraction.MINUS_ONE),
                    LinearOp.LE, BigFraction.ZERO),
                ExactLinear.of(IntArray(2) { Term.ofIntVar(it) },
                    listOf(BigFraction.ofLong(2), BigFraction.MINUS_ONE), LinearOp.LE, BigFraction.ZERO),
            )), pbLearning = false).apply { enableIntegerRowLearning() }
        val cp = CpSearchComponent(native, intArrayOf(4, 7, 9))
        val shared = SearchSession(listOf(cp), atoms = SearchAtomRegistry(0))
        assertIs<ComponentResult.Consistent>(shared.initialize())
        assertIs<ComponentResult.Consistent>(shared.push(SearchDecision.IntAtMost(9, 7)))
        assertIs<ComponentResult.Conflict>(shared.push(SearchDecision.IntAtLeast(4, 2)))
        val backjump = assertIs<SearchConflictResolution.Backjump>(cp.resolveConflict(shared)).conflict
        shared.popTo(backjump.decisionLevel)

        assertIs<SearchLearnedConflictResult.Resume>(backjump.apply(shared))

        assertEquals(1L, shared.intUpperBound(4))
        assertTrue((native.learnedLifecycle()["learningIntegerConsumed"] ?: 0L) > 0L)
    }

    @Test
    fun `activated exact rows should retain clause fallback in either guard state`() {
        for (truth in listOf(false, true)) {
            val state = PropagationState(Problem(1, 1, arrayOf(IntDomain(0, 6)), arrayOf(
                ExactLinear.of(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ONE),
                    LinearOp.LE, BigFraction.ofLong(3), activator = 0),
            )), Assumptions.None)
            assertTrue(state.pinBool(0, truth))

            val source = GuardedIntegerSource(state).source(0)

            assertNull(source)
        }
    }

    @Test
    fun `a wide exact projection should retain clause fallback`() {
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 6)), arrayOf(
            ExactLinear.of(intArrayOf(Term.ofIntVar(0)), listOf(BigFraction.ofLong(Long.MIN_VALUE)),
                LinearOp.LE, BigFraction.ZERO),
        )), Assumptions.None)

        val source = GuardedIntegerSource(state).source(0)

        assertNull(source)
    }

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
        for ((op, truth, sign) in listOf(Triple(LinearOp.LE, true, 1), Triple(LinearOp.GE, false, 1),
            Triple(LinearOp.GE, true, -1), Triple(LinearOp.LE, false, -1), Triple(LinearOp.EQ, true, 1),
            Triple(LinearOp.NE, false, 1), Triple(LinearOp.EQ, true, -1), Triple(LinearOp.NE, false, -1))) {
            val native = PropagationSession(Problem(1, 3,
                arrayOf(IntDomain(0, 6), IntDomain(0, 12), IntDomain(0, 24)), arrayOf(
                    ReifiedLinear(0, intArrayOf(2 * sign, sign, -sign), intArrayOf(0, 1, 2),
                        op, if (truth || op == LinearOp.NE) 0 else if (op == LinearOp.GE) 1 else -1),
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
