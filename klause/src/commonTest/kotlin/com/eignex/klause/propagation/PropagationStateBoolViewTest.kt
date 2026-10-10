package com.eignex.klause.propagation

import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PropagationStateBoolViewTest {

    private fun newState(numBoolVars: Int): PropagationState {
        val problem = Problem(
            numBoolVars = numBoolVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        return PropagationState(problem, Assumptions.None)
    }

    @Test
    fun `set null clears assignment back to unassigned`() {
        val s = newState(3)
        s.boolValues[0] = true
        assertEquals(true, s.boolValues[0])
        s.boolValues[0] = null
        assertNull(s.boolValues[0])
    }

    @Test
    fun `indices across a word boundary stay independent`() {
        // 130 vars crosses two LongArray word boundaries; verify no bleed.
        val s = newState(130)
        s.boolValues[63] = true
        s.boolValues[64] = false
        s.boolValues[129] = true
        assertEquals(true, s.boolValues[63])
        assertEquals(false, s.boolValues[64])
        assertNull(s.boolValues[65])
        assertEquals(true, s.boolValues[129])
        assertNull(s.boolValues[128])
    }

}
