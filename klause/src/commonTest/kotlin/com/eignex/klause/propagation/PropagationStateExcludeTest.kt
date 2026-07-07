package com.eignex.klause.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
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
    fun `excludeIntValue at min becomes a one-step bound tighten`() {
        val s = state(arrayOf(IntDomain(1, 5)))
        assertTrue(s.excludeIntValue(0, 1))
        assertEquals(2, s.intDomains[0].min)
        assertEquals(5, s.intDomains[0].max)
    }

    @Test
    fun `excludeIntValue at max becomes a one-step bound tighten`() {
        val s = state(arrayOf(IntDomain(1, 5)))
        assertTrue(s.excludeIntValue(0, 5))
        assertEquals(1, s.intDomains[0].min)
        assertEquals(4, s.intDomains[0].max)
    }

    @Test
    fun `excludeIntValue interior creates sparse domain`() {
        val s = state(arrayOf(IntDomain(1, 5)))
        assertTrue(s.excludeIntValue(0, 3))
        val d = s.intDomains[0]
        assertEquals(1, d.min)
        assertEquals(5, d.max)
        assertEquals(4, d.size)
        assertFalse(3 in d)
        assertTrue(2 in d)
        assertTrue(4 in d)
    }

    @Test
    fun `excludeIntValue absent is no-op`() {
        val s = state(arrayOf(IntDomain(1, 5)))
        assertTrue(s.excludeIntValue(0, 99))
        assertEquals(IntDomain(1, 5), s.intDomains[0])
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
        assertEquals(3, d.size)
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
        assertEquals(2, d.size)
    }

    @Test
    fun `stacked exclusions accumulate holes`() {
        val s = state(arrayOf(IntDomain(0, 10)))
        assertTrue(s.excludeIntValue(0, 3))
        assertTrue(s.excludeIntValue(0, 5))
        assertTrue(s.excludeIntValue(0, 7))
        val d = s.intDomains[0]
        assertEquals(0, d.min)
        assertEquals(10, d.max)
        assertEquals(8, d.size)
        for (h in longArrayOf(3, 5, 7)) assertFalse(h in d, "$h should be a hole")
        for (k in longArrayOf(0, 1, 2, 4, 6, 8, 9, 10)) assertTrue(k in d, "$k should remain in domain")
    }
}
