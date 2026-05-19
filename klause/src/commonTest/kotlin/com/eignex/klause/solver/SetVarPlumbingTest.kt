package com.eignex.klause.solver

import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Session-1 smoke tests: Problem + PropagationState + Assignment + Sample carry set-var
 * state correctly. No factor catalog yet — these exercise the plumbing.
 */
class SetVarPlumbingTest {

    @Test
    fun `problem with one set var passes invariants and exposes occurrences`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 0,
            intDomains = emptyArray(),
            numSetVars = 1,
            setDomains = arrayOf(SetDomain.unrestricted(5)),
            factors = emptyList(),
        )
        assertEquals(1, problem.numSetVars)
        assertEquals(5, problem.setDomains[0].universeSize)
        assertEquals(0, problem.setDomains[0].cardMin)
        assertEquals(5, problem.setDomains[0].cardMax)
        assertEquals(emptyList(), problem.setOccurrences[0].toList())  // no factors mention it
    }

    @Test
    fun `propagation state mirrors set domains and require excludes element`() {
        val problem = Problem(
            numBoolVars = 0, numIntVars = 0, intDomains = emptyArray(),
            numSetVars = 1, setDomains = arrayOf(SetDomain.unrestricted(4)),
            factors = emptyList(),
        )
        val state = PropagationState(problem, Assumptions.None)
        assertTrue(state.setPossible[0].get(2))
        assertFalse(state.setRequired[0].get(2))

        assertTrue(state.requireElement(0, 2))
        assertTrue(state.setRequired[0].get(2))

        // Excluding a required element contradicts.
        assertFalse(state.excludeElement(0, 2))

        // Excluding a non-required element succeeds.
        assertTrue(state.excludeElement(0, 3))
        assertFalse(state.setPossible[0].get(3))

        // Requiring an already-excluded element contradicts.
        assertFalse(state.requireElement(0, 3))
    }

    @Test
    fun `assignment snapshot round-trips set membership`() {
        val a = Assignment(numBoolVars = 0, numIntVars = 0, numSetVars = 2,
                           setUniverseSizes = intArrayOf(3, 3))
        a.setInclude(0, 1)
        a.setInclude(0, 2)
        a.setInclude(1, 0)
        val s = a.snapshot()
        assertEquals(listOf(1, 2), s.sets[0].toList())
        assertEquals(listOf(0), s.sets[1].toList())
    }

    @Test
    fun `sample hamming distance counts set symmetric difference`() {
        val a = Sample(
            bools = BooleanArray(0),
            ints = IntArray(0),
            sets = arrayOf(intArrayOf(1, 2, 3)),
        )
        val b = Sample(
            bools = BooleanArray(0),
            ints = IntArray(0),
            sets = arrayOf(intArrayOf(2, 3, 4)),
        )
        // Symmetric diff: {1, 4} — distance 2.
        assertEquals(2, a.hammingDistanceTo(b))
    }

    @Test
    fun `singleton set domain is fixed`() {
        val d = SetDomain.singleton(5, intArrayOf(1, 3))
        assertTrue(d.isFixed)
        assertEquals(2, d.cardMin)
        assertEquals(2, d.cardMax)
    }
}
