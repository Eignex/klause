package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Documents the three-valued semantics of [PropagationState.boolValues] — the [Bits]-backed
 * `BoolView` wrapper that replaced the old `Array<Boolean?>` storage. Read-write-clear is
 * the contract every factor depends on; this test pins it down so a future tweak to the
 * underlying packed storage can't silently break the null-tracking invariant.
 */
class BoolViewTest {

    private fun newState(numBoolVars: Int): PropagationState {
        val problem = Problem(
            numBoolVars = numBoolVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyList(),
        )
        return PropagationState(problem, Assumptions.None)
    }

    @Test
    fun `unassigned vars read as null`() {
        val s = newState(5)
        for (v in 0 until 5) assertNull(s.boolValues[v])
    }

    @Test
    fun `set true and false round-trip`() {
        val s = newState(4)
        s.boolValues[0] = true
        s.boolValues[1] = false
        s.boolValues[3] = true
        assertEquals(true, s.boolValues[0])
        assertEquals(false, s.boolValues[1])
        assertNull(s.boolValues[2])
        assertEquals(true, s.boolValues[3])
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
    fun `flipping assigned value updates without clearing assigned bit`() {
        val s = newState(2)
        s.boolValues[0] = true
        s.boolValues[0] = false
        assertEquals(false, s.boolValues[0])
        s.boolValues[0] = true
        assertEquals(true, s.boolValues[0])
    }

    @Test
    fun `bit-boundary indices isolated`() {
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

    @Test
    fun `size and indices match numBoolVars`() {
        val s = newState(7)
        assertEquals(7, s.boolValues.size)
        assertEquals(0 until 7, s.boolValues.indices)
    }
}
