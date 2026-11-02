package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LearnedIntegerPropagatorTest {
    @Test
    fun `binary cardinality should preserve signed deductions with one eager reason`() {
        val row = requireNotNull(IntegerConflictRow.normalized(mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE))
        val states = List(2) {
            PropagationState(Problem(0, 3, Array(3) { IntDomain(0, 1) }, emptyArray()), Assumptions.None).apply {
                undoLogging = true
                assertTrue(setIntMinAsDecision(0, 1))
            }
        }
        val ordinary = LearnedIntegerPropagator(row)
        val specialized = LearnedIntegerPropagator(row, states[1].learnedCardinalityLimit(row))

        assertTrue(ordinary.propagate(states[0], -1))
        assertTrue(specialized.propagate(states[1], -1))

        assertTrue(specialized.isCardinality)
        for (index in row.variables.indices) {
            assertEquals(states[0].intDomains[index], states[1].intDomains[index])
            assertContentEquals(states[0].intMinAntecedents[index], states[1].intMinAntecedents[index])
            assertContentEquals(states[0].intMaxAntecedents[index], states[1].intMaxAntecedents[index])
        }
        val reason = assertNotNull(states[1].intMaxAntecedents[1])
        assertSame(reason, states[1].intMinAntecedents[2])
        assertContentEquals(intArrayOf(Lit.make(states[1].atomVarGe(0, 1), false)), reason)
        assertTrue(states[1].work < states[0].work)
    }

    @Test
    fun `binary cardinality should preserve both signed guards`() {
        for (positive in listOf(false, true)) {
            val state = PropagationState(Problem(1, 3, Array(3) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
            state.undoLogging = true
            assertTrue(state.setIntMinAsDecision(0, 1))
            val guard = Lit.make(0, positive)
            val row = requireNotNull(IntegerConflictRow.normalized(mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, guard))
            val propagator = LearnedIntegerPropagator(row, state.learnedCardinalityLimit(row))
            assertTrue(propagator.propagate(state, -1))
            assertEquals(1L, state.intDomains[1].max)
            val mark = state.mark()
            assertTrue(state.pinBoolAsDecision(0, !positive))
            assertTrue(propagator.propagate(state, -1))
            assertEquals(1L, state.intDomains[1].max)
            state.undoTo(mark)
            assertTrue(state.pinBoolAsDecision(0, positive))

            assertTrue(propagator.propagate(state, -1))

            assertEquals(0L, state.intDomains[1].max)
            assertEquals(1L, state.intDomains[2].min)
            assertTrue(assertNotNull(state.intMaxAntecedents[1]).contains(guard xor 1))
        }
    }

    @Test
    fun `binary cardinality should preserve linear conflict explanations`() {
        val state = PropagationState(Problem(0, 3, Array(3) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        state.undoLogging = true
        assertTrue(state.setIntMinAsDecision(0, 1))
        assertTrue(state.setIntMinAsDecision(1, 1))
        val row = requireNotNull(IntegerConflictRow.normalized(mapOf(0 to 1L, 1 to 1L, 2 to 1L), 1L, Lit.NONE))
        val ordinary = LearnedIntegerPropagator(row)
        val specialized = LearnedIntegerPropagator(row, state.learnedCardinalityLimit(row))

        assertFalse(ordinary.propagate(state, -1))
        assertFalse(specialized.propagate(state, -1))

        val reason = assertNotNull(specialized.conflictReason(state, -1))
        assertContentEquals(ordinary.conflictReason(state, -1), reason)
        assertEquals(2, reason.size)
    }

    @Test
    fun `a nonbinary declaration should retain linear bound propagation`() {
        val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(0, 2) }, emptyArray()), Assumptions.None)
        assertTrue(state.setIntMaxAsDecision(0, 0))
        val row = requireNotNull(IntegerConflictRow.normalized(mapOf(0 to 1L, 1 to 1L), 1L, Lit.NONE))
        val propagator = LearnedIntegerPropagator(row, state.learnedCardinalityLimit(row))

        assertTrue(propagator.propagate(state, -1))

        assertFalse(propagator.isCardinality)
        assertEquals(1L, state.intDomains[1].max)
    }

    @Test
    fun `a weighted binary row should retain linear bound propagation`() {
        val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(0, 1) }, emptyArray()), Assumptions.None)
        state.undoLogging = true
        assertTrue(state.setIntMinAsDecision(0, 1))
        val row = requireNotNull(IntegerConflictRow.normalized(mapOf(0 to 2L, 1 to 1L), 2L, Lit.NONE))
        val propagator = LearnedIntegerPropagator(row, state.learnedCardinalityLimit(row))

        assertTrue(propagator.propagate(state, -1))

        assertFalse(propagator.isCardinality)
        assertEquals(0L, state.intDomains[1].max)
    }

    @Test
    fun `a narrowed binary box should not qualify a wider declaration`() {
        val state = PropagationState(Problem(0, 2, Array(2) { IntDomain(0, 2) }, emptyArray()), Assumptions.None)
        assertTrue(state.setIntMaxAsDecision(0, 1))
        assertTrue(state.setIntMaxAsDecision(1, 1))
        val row = requireNotNull(IntegerConflictRow.normalized(mapOf(0 to 1L, 1 to 1L), 1L, Lit.NONE))

        val propagator = LearnedIntegerPropagator(row, state.learnedCardinalityLimit(row))

        assertFalse(propagator.isCardinality)
    }

    @Test
    fun `a binary row should preserve eager history and retire after retraction`() {
        val native = PropagationSession(Problem(1, 3, Array(3) { IntDomain(0, 1) }, arrayOf(
            Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.LE, 2),
        )))
        native.enableIntegerRowLearning()
        val row = requireNotNull(IntegerConflictRow.normalized(mapOf(0 to 1L, 1 to 1L, 2 to -1L), 0L, Lit.NONE))
        assertIs<PropagationResult.Implied>(native.addLearnedIntegerRow(row, 1))
        assertIs<PropagationResult.Implied>(native.pinIntAtLeast(0, 1))
        val state = native.explanationState
        val reason = assertNotNull(state.intMaxAntecedents[1])
        val frozen = reason.copyOf()
        assertEquals(0L, native.intDomain(1).max)
        assertEquals(1L, native.intDomain(2).min)
        native.forgetLearnedClauses { _, _ -> false }
        assertEquals(1, state.learned.integerRows?.size)
        native.popToLevel(0)

        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 0))

        assertEquals(0L, native.intDomain(0).max)
        assertEquals(0L, native.intDomain(1).max)
        assertContentEquals(frozen, reason)
        assertEquals(1L, native.learnedLifecycle()["learningIntegerCardinalityConsumed"])
        native.popToLevel(0)
        assertIs<PropagationResult.Implied>(native.pinBool(0, true))
        native.forgetLearnedClauses { _, _ -> false }
        assertTrue(state.learned.integerRows.isNullOrEmpty())
        assertEquals(1L, native.learnedLifecycle()["learningIntegerRetired"])
        assertIs<PropagationResult.Implied>(native.pinIntAtMost(2, 0))
        assertEquals(1L, native.intDomain(1).max)
    }
}
