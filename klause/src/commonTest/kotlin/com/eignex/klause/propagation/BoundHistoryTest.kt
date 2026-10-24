package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundHistoryTest {
    @Test
    fun `hole witnesses should retain their historical position across a bound undo`() {
        for (prepared in listOf(false, true)) {
            val problem = Problem(1, 1, arrayOf(IntDomain(0, 9)), emptyArray())
            val direct = PropagationState(problem, Assumptions.None)
            val state = if (prepared) {
                PropagationState.preparation(direct.projection, pbLearning = false)
                    .asSequence().filterNotNull().last()
            } else {
                direct
            }
            state.undoLogging = true
            state.levelToDecisionVar.add(0)
            state.currentLevel = 1
            check(state.pinBool(0, true))
            val before = state.undo.size
            val reason = intArrayOf(Lit.make(0, false))
            check(state.excludeIntValue(0, 3, reason))
            val position = state.carvedAt(0, 3)
            val carved = state.mark()
            check(state.tightenIntMin(0, 6))
            val atom = state.atomVarEq(0, 3) - problem.numBoolVars

            state.undoTo(carved)

            assertEquals(position, state.carvedAt(0, 3))
            assertTrue(state.inDomainAt(0, 3, before))
            assertFalse(state.inDomainAt(0, 3, position))
            assertEquals(1, state.holeLevelFor(0, 3))
            assertEquals(reason.toList(), state.holeReasonFor(0, 3)?.toList())
            assertEquals(false, state.atomCurrentTruth(atom))
            assertEquals(reason.toList(), assertNotNull(state.atomAntecedentsDerived(atom)).toList())
        }
    }

    @Test
    fun `hole witnesses should retract before the value is carved in a sibling`() {
        for (prepared in listOf(false, true)) {
            val problem = Problem(2, 1, arrayOf(IntDomain(0, 9)), emptyArray())
            val direct = PropagationState(problem, Assumptions.None)
            val state = if (prepared) {
                PropagationState.preparation(direct.projection, pbLearning = false)
                    .asSequence().filterNotNull().last()
            } else {
                direct
            }
            state.undoLogging = true
            val root = state.mark()
            state.levelToDecisionVar.add(0)
            state.currentLevel = 1
            check(state.pinBool(0, true))
            check(state.excludeIntValue(0, 3, intArrayOf(Lit.make(0, false))))
            val atom = state.atomVarEq(0, 3) - problem.numBoolVars
            state.undoTo(root)
            assertFalse(state.holeHistHas(0, 3))
            assertEquals(-1, state.carvedAt(0, 3))
            assertEquals(0, state.holeLevelFor(0, 3))
            assertNull(state.holeReasonFor(0, 3))
            state.levelToDecisionVar.add(1)
            state.currentLevel = 1
            check(state.pinBool(1, true))
            val reason = intArrayOf(Lit.make(1, false))

            check(state.excludeIntValue(0, 3, reason))

            assertTrue(state.holeHistHas(0, 3))
            assertEquals(1, state.atomLevelForConflict(atom))
            assertEquals(reason.toList(), assertNotNull(state.atomAntecedentsDerived(atom)).toList())
        }
    }

    @Test
    fun `root hole membership should preserve gaps across insertion orders`() {
        for (values in listOf(
            longArrayOf(-2, -1, 0, 2, 3),
            longArrayOf(3, 2, 0, -1, -2),
            longArrayOf(-2, 0, -1, 3, 2, -1, 2),
        )) {
            val history = RootHoleHistory()
            values.forEach(history::add)

            for (value in -3L..4L) {
                assertEquals(value in values, value in history, "value $value")
            }
        }
    }

    @Test
    fun `extreme root holes should keep separate neighborhoods`() {
        val history = RootHoleHistory()
        for (value in longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1L, Long.MIN_VALUE + 1L, 0L)) {
            history.add(value)
        }

        assertTrue(Long.MIN_VALUE in history)
        assertTrue(Long.MIN_VALUE + 1L in history)
        assertFalse(Long.MIN_VALUE + 2L in history)
        assertTrue(0L in history)
        assertFalse(Long.MAX_VALUE - 2L in history)
        assertTrue(Long.MAX_VALUE - 1L in history)
        assertTrue(Long.MAX_VALUE in history)
    }
}
