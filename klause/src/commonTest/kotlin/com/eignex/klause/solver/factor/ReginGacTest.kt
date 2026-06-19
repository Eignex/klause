package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.global.AllDifferent
import com.eignex.klause.solver.factor.global.GlobalCardinality
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests asserting Régin-style GAC pruning — strictly stronger than Hall-interval bound
 * consistency. Each test sets up a scenario where bound consistency would leave some
 * inferable value untouched, then asserts Régin prunes it.
 */
class ReginGacTest {

    private fun stateWith(factor: AllDifferent, domains: Array<IntDomain>): PropagationState {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = domains.size,
            intDomains = domains,
            factors = arrayOf<Factor>(factor),
        )
        return PropagationState(problem, Assumptions.None)
    }

    private fun stateWith(factor: GlobalCardinality, domains: Array<IntDomain>): PropagationState {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = domains.size,
            intDomains = domains,
            factors = arrayOf<Factor>(factor),
        )
        return PropagationState(problem, Assumptions.None)
    }

    @Test
    fun `AllDifferent non-contiguous Hall set prunes interior value`() {
        // x0, x1 ∈ {1, 3} (sparse). x2 ∈ {1, 2, 3}. {1, 3} is a non-contiguous Hall set
        // monopolised by x0+x1 — Régin must prune both 1 and 3 from x2, leaving {2}. Bound
        // consistency only checks contiguous intervals and misses this.
        val d01 = IntDomain(1, 3).excludeValue(2) // sparse {1, 3}
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 3)
        val state = stateWith(factor, arrayOf(d01, d01, IntDomain(1, 3)))
        assertTrue(factor.propagate(state, factorId = 0))
        val d2 = state.intDomains[2]
        assertEquals(2, d2.min)
        assertEquals(2, d2.max)
    }

    @Test
    fun `AllDifferent Hall set prunes interior of spanning var`() {
        // x0, x1 ∈ {1, 2}, x2 ∈ {1, 2, 3, 4, 5}. Hall set {1, 2} monopolised; Régin removes
        // both from x2.
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 5)
        val state = stateWith(
            factor,
            arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(1, 5)),
        )
        assertTrue(factor.propagate(state, factorId = 0))
        val d2 = state.intDomains[2]
        assertEquals(3, d2.min)
        assertEquals(5, d2.max)
        assertFalse(1 in d2)
        assertFalse(2 in d2)
    }

    @Test
    fun `AllDifferent infeasible when pigeonhole violated`() {
        // 3 vars, 2 values total — infeasible.
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 1, domainSize = 2)
        val state = stateWith(
            factor,
            arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(1, 2)),
        )
        assertFalse(factor.propagate(state, factorId = 0))
    }

    @Test
    fun `AllDifferent singleton conflict detected`() {
        val factor = AllDifferent(intArrayOf(0, 1), domainMin = 1, domainSize = 5)
        val state = stateWith(factor, arrayOf(IntDomain(3, 3), IntDomain(3, 3)))
        assertFalse(factor.propagate(state, factorId = 0))
    }

    @Test
    fun `GCC lo-bound forces possible matchers to be pinned`() {
        // cover = [5], lo = [3], hi = [3]. xs has exactly 3 vars all containing 5.
        // Régin: every var must take 5.
        val factor = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = intArrayOf(5),
            countLow = intArrayOf(3),
            countHigh = intArrayOf(3),
            closed = false,
        )
        val state = stateWith(
            factor,
            arrayOf(IntDomain(4, 6), IntDomain(4, 6), IntDomain(4, 6)),
        )
        assertTrue(factor.propagate(state, factorId = 0))
        for (i in 0..2) {
            val d = state.intDomains[i]
            assertEquals(5, d.min, "var $i min")
            assertEquals(5, d.max, "var $i max")
        }
    }

    @Test
    fun `GCC hi-bound saturated prunes value from remaining vars`() {
        // cover = [7], lo = [0], hi = [1]. x0 is pinned to 7 (uses up the cap). x1, x2
        // currently include 7 in their domains — Régin must punch 7 out.
        val factor = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = intArrayOf(7),
            countLow = intArrayOf(0),
            countHigh = intArrayOf(1),
            closed = false,
        )
        val state = stateWith(
            factor,
            arrayOf(IntDomain(7, 7), IntDomain(6, 8), IntDomain(6, 8)),
        )
        assertTrue(factor.propagate(state, factorId = 0))
        assertFalse(7 in state.intDomains[1])
        assertFalse(7 in state.intDomains[2])
    }

    @Test
    fun `GCC closed restricts xs to cover values`() {
        // cover = {2, 3}, lo = [0, 0], hi = [3, 3], closed = true.
        // x0 ∈ [1, 4] → must drop 1 and 4.
        val factor = GlobalCardinality(
            xs = intArrayOf(0, 1),
            cover = intArrayOf(2, 3),
            countLow = intArrayOf(0, 0),
            countHigh = intArrayOf(3, 3),
            closed = true,
        )
        val state = stateWith(factor, arrayOf(IntDomain(1, 4), IntDomain(2, 3)))
        assertTrue(factor.propagate(state, factorId = 0))
        val d0 = state.intDomains[0]
        assertFalse(1 in d0)
        assertFalse(4 in d0)
        assertTrue(2 in d0)
        assertTrue(3 in d0)
    }

    @Test
    fun `GCC infeasible when lower bound exceeds possible`() {
        // cover = [9], lo = [2], hi = [5]. xs has 3 vars, only 1 contains 9.
        val factor = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = intArrayOf(9),
            countLow = intArrayOf(2),
            countHigh = intArrayOf(5),
            closed = false,
        )
        val state = stateWith(
            factor,
            arrayOf(IntDomain(8, 10), IntDomain(0, 1), IntDomain(0, 1)),
        )
        assertFalse(factor.propagate(state, factorId = 0))
    }

    @Test
    fun `GCC tightens countVars from definite and possible matchers`() {
        // cover = [3, 7], with two count vars.
        // xs: x0=3 (singleton), x1∈{3,7}, x2∈{3,7}. Definite-3=1, possible-3=3.
        // Definite-7=0, possible-7=2.
        // countVars[0] domain [0, 3] → should tighten min to 1, max to 3.
        // countVars[1] domain [0, 3] → should tighten min to 0, max to 2.
        val factor = GlobalCardinality(
            xs = intArrayOf(0, 1, 2),
            cover = intArrayOf(3, 7),
            countVars = intArrayOf(3, 4),
            closed = false,
        )
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(3, 3),
                IntDomain(3, 7).excludeValue(4).excludeValue(5).excludeValue(6),
                IntDomain(3, 7).excludeValue(4).excludeValue(5).excludeValue(6),
                IntDomain(0, 3),
                IntDomain(0, 3),
            ),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        assertTrue(factor.propagate(state, factorId = 0))
        assertEquals(1, state.intDomains[3].min)
        assertEquals(3, state.intDomains[3].max)
        assertEquals(0, state.intDomains[4].min)
        assertEquals(2, state.intDomains[4].max)
    }
}
