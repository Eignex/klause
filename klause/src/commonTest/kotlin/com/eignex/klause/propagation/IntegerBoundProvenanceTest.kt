package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntegerBoundProvenanceTest {
    @Test
    fun `a raw endpoint should decline a stronger sparse landing`() {
        for (lower in listOf(false, true)) {
            val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 8).excludeValue(4)), emptyArray()),
                Assumptions.None)
            state.undoLogging = true
            assertTrue(if (lower) state.tightenIntMin(0, 4) else state.tightenIntMax(0, 4))

            assertFalse(state.rawIntegerEndpointSupported(0, lower, 4, 0))
        }
    }

    @Test
    fun `a raw endpoint should decline clipped historical exclusions`() {
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 8).excludeValue(4)), emptyArray()),
            Assumptions.None)
        state.undoLogging = true
        assertTrue(state.tightenIntMax(0, 3))

        assertFalse(state.rawIntegerEndpointSupported(0, lower = false, requested = 3, trail = 0))
    }

    @Test
    fun `a later carve should not become a clipped historical exclusion`() {
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 8)), emptyArray()), Assumptions.None)
        state.undoLogging = true
        assertTrue(state.tightenIntMin(0, 1))
        val trail = state.undo.size
        assertTrue(state.excludeIntValue(0, 4))

        assertTrue(state.rawIntegerEndpointSupported(0, lower = false, requested = 3, trail = trail))
        assertFalse(state.rawIntegerEndpointSupported(0, lower = false, requested = 3, trail = state.undo.size))
    }

    @Test
    fun `a sparse endpoint walk should decline outside its allowance`() {
        val state = PropagationState(Problem(0, 1, arrayOf(IntDomain(0, 1000).excludeValue(2)), emptyArray()),
            Assumptions.None)

        assertFalse(state.rawIntegerEndpointSupported(0, lower = false, requested = 3, trail = 0))
    }
}
