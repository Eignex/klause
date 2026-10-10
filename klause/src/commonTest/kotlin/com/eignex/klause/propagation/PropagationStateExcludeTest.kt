package com.eignex.klause.propagation

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.propagation.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PropagationStateExcludeTest {

    private fun state(domains: Array<IntDomain>): PropagationState {
        val p = Problem(
            numBoolVars = 0,
            numIntVars = domains.size,
            intDomains = domains,
            factors = emptyArray(),
        )
        return PropagationState(p, Assumptions.None)
    }

    @Test
    fun `excludeIntValue interior creates sparse domain`() {
        val s = state(arrayOf(IntDomain(1, 5)))
        assertTrue(s.excludeIntValue(0, 3))
        val d = s.intDomains[0]
        assertEquals(1, d.min)
        assertEquals(5, d.max)
        assertEquals(4, d.values.size)
        assertFalse(3 in d)
        assertTrue(2 in d)
        assertTrue(4 in d)
    }

    @Test
    fun `excludeIntValue emptying a singleton domain conflicts`() {
        val s = state(arrayOf(IntDomain(5, 5)))
        assertFalse(s.excludeIntValue(0, 5))
    }

    @Test
    fun `tightenIntMin past holes preserves sparse representation`() {
        // Build a sparse domain by punching a hole at 3, then tighten min past 2.
        val s = state(arrayOf(IntDomain(1, 5)))
        assertTrue(s.excludeIntValue(0, 3)) // domain = {1, 2, 4, 5}
        assertTrue(s.tightenIntMin(0, 2)) // domain should be {2, 4, 5}
        val d = s.intDomains[0]
        assertEquals(2, d.min)
        assertEquals(5, d.max)
        assertEquals(3, d.values.size)
        assertFalse(3 in d, "hole at 3 should survive the lower-bound tighten")
        assertTrue(2 in d)
        assertTrue(4 in d)
    }

    @Test
    fun `tightenIntMax through holes lands past them`() {
        // Sparse domain {1, 2, 4, 5}, then ask for max <= 3 → new max should land on 2.
        val s = state(arrayOf(IntDomain(1, 5)))
        assertTrue(s.excludeIntValue(0, 3))
        assertTrue(s.tightenIntMax(0, 3)) // 3 itself is a hole; lands at 2.
        val d = s.intDomains[0]
        assertEquals(1, d.min)
        assertEquals(2, d.max)
        assertEquals(2, d.values.size)
    }

}
