package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.plus
import com.eignex.util.numbers.shl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LearnedExactPbPropagatorTest {
    @Test
    fun `interrupted exact PB admission should publish no retained row`() {
        for (deadline in listOf(false, true)) {
            val native = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()), pbLearning = false)
            val weight = bigIntOf(1) shl 8192
            val admit = {
                native.addLearnedPb(arrayOf(weight, weight, bigIntOf(1)), IntArray(3) { Lit.make(it, true) },
                    weight + bigIntOf(1), 3)
            }

            val result = if (deadline) native.withCancellation(ExecutionControl { true }, admit)
            else native.withWorkLimit(0L, admit)

            assertIs<PropagationResult.Implied>(result)
            assertTrue(native.fixpointCancelled)
            assertEquals(0, native.learnedClauseCount)
            assertEquals(null, native.boolValue(0))
        }
    }

    @Test
    fun `a wide retained row should force sibling assignments after undo`() {
        val native = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()), pbLearning = false)
        val weight = bigIntOf(Long.MAX_VALUE) + bigIntOf(1)
        assertIs<PropagationResult.Implied>(native.addLearnedPb(arrayOf(weight, weight, bigIntOf(1)),
            IntArray(3) { Lit.make(it, true) }, weight + bigIntOf(1), 3))
        val state = native.explanationState
        val retained = assertIs<LearnedExactPbPropagator>(state.factorAt(0))

        assertIs<PropagationResult.Implied>(native.pinBool(0, false))

        assertEquals(true, state.boolValues[1])
        assertEquals(true, state.boolValues[2])
        assertSame(retained, state.factorAt(state.boolReason[1]))
        assertEquals(1L, native.learnedLifecycle()["learningPbConsumed"])
        native.popToLevel(0)
        assertIs<PropagationResult.Implied>(native.pinBool(1, false))
        assertEquals(true, state.boolValues[0])
        assertEquals(true, state.boolValues[2])
        assertSame(retained, state.factorAt(state.boolReason[0]))
    }

    @Test
    fun `a wide retained row should retire after its Boolean reasons retract`() {
        val native = PropagationSession(Problem(3, 0, emptyArray(), emptyArray()), pbLearning = false)
        val weight = bigIntOf(Long.MAX_VALUE) + bigIntOf(1)
        native.addLearnedPb(arrayOf(weight, weight, bigIntOf(1)), IntArray(3) { Lit.make(it, true) },
            weight + bigIntOf(1), 3)
        assertIs<PropagationResult.Implied>(native.pinBool(0, false))
        native.forgetLearnedClauses { _, _ -> false }
        assertEquals(1, native.learnedClauseCount)
        native.popToLevel(0)

        native.forgetLearnedClauses { _, _ -> false }

        assertEquals(0, native.learnedClauseCount)
        assertEquals(1L, native.learnedLifecycle()["learningPbRetired"])
        assertIs<PropagationResult.Implied>(native.pinBool(0, false))
        assertEquals(null, native.explanationState.boolValues[1])
    }
}
