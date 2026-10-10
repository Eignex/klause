package com.eignex.klause.factor.arithmetic.internals

import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReifiedAuxTailTest {
    @Test
    fun `a matching settled indicator needs no additional reason`() {
        for (value in listOf(false, true)) {
            val state = PropagationState(Problem(1, 0, emptyArray(), emptyList()), Assumptions.None)
            assertTrue(state.pinBool(0, value))

            val result = state.reifiedAuxTail(
                0, alwaysHolds = value, neverHolds = !value,
                pinAntecedent = { error("matching pin needs no reason") },
                propagateTrue = { error("settled body needs no propagation") },
                propagateFalse = { error("settled body needs no propagation") },
            )

            assertTrue(result)
            assertEquals(value, state.boolValues[0])
        }
    }

    @Test
    fun `a newly settled indicator retains its implication reason`() {
        for (value in listOf(false, true)) {
            val state = PropagationState(Problem(2, 0, emptyArray(), emptyList()), Assumptions.None)
            assertTrue(state.pinBool(1, true))
            val premise = Lit.make(1, false)

            val result = state.reifiedAuxTail(
                0, alwaysHolds = value, neverHolds = !value,
                pinAntecedent = { intArrayOf(premise) },
                propagateTrue = { error("settled body needs no propagation") },
                propagateFalse = { error("settled body needs no propagation") },
            )

            assertTrue(result)
            assertEquals(value, state.boolValues[0])
            assertEquals(listOf(premise), state.boolAntecedents[0]?.toList())
        }
    }

    @Test
    fun `an opposite settled indicator still reports conflict`() {
        for (value in listOf(false, true)) {
            val state = PropagationState(Problem(1, 0, emptyArray(), emptyList()), Assumptions.None)
            assertTrue(state.pinBool(0, !value))

            val result = state.reifiedAuxTail(
                0, alwaysHolds = value, neverHolds = !value,
                pinAntecedent = { null },
                propagateTrue = { error("settled body needs no propagation") },
                propagateFalse = { error("settled body needs no propagation") },
            )

            assertFalse(result)
        }
    }
}
