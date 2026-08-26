package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The incremental objective bool lower bound ([PropagationState.objectiveBoolLowerBound]) must equal the
 * from-scratch scan `Σ_b contribution(b)` at every node — it is a reversible-trail accumulator standing in
 * for that O(numBoolVars) rescan, and a stale value would prune wrongly.
 */
class ObjectiveBoolLowerBoundTest {

    /** Reference bound: pinned-true contributes its weight, pinned-false 0, unpinned min(weight, 0). */
    private fun scan(s: PropagationSession, weights: LongArray): Long {
        var total = 0L
        for (b in weights.indices) {
            val v = s.boolValue(b)
            total += when {
                v == true -> weights[b]
                v == false -> 0L
                weights[b] < 0L -> weights[b]
                else -> 0L
            }
        }
        return total
    }

    @Test
    fun `incremental bound matches the full scan across pins and backtracks`() {
        val weights = longArrayOf(5, -3, 2, -7, 4)
        // Clause ¬x0 ∨ x1 makes pinning x0=true propagate x1=true, so a propagated pin (not just a
        // decision) must also fold into the accumulator.
        val p = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true)))),
        )
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        assertTrue(s.installObjectiveBoolBound(weights))
        assertEquals(scan(s, weights), s.objectiveBoolLowerBound(), "root baseline")

        s.pinBool(0, true) // decision x0=true; propagates x1=true
        assertEquals(scan(s, weights), s.objectiveBoolLowerBound(), "after a propagating pin")
        s.pinBool(2, false)
        assertEquals(scan(s, weights), s.objectiveBoolLowerBound())
        s.pinBool(3, true)
        assertEquals(scan(s, weights), s.objectiveBoolLowerBound())

        s.popToLevel(1) // drop x2, x3 (and x1 stays, forced by x0 at level 1)
        assertEquals(scan(s, weights), s.objectiveBoolLowerBound(), "after backtrack")

        // Re-descend the freed level to a DIFFERENT value than before — the case a naive trail-length
        // diff gets wrong.
        s.pinBool(3, false)
        assertEquals(scan(s, weights), s.objectiveBoolLowerBound(), "after redescend to a new value")
        s.pinBool(2, true)
        assertEquals(scan(s, weights), s.objectiveBoolLowerBound())

        s.popToLevel(0) // full unwind to the root
        assertEquals(scan(s, weights), s.objectiveBoolLowerBound(), "back at root")
        assertEquals(0L + weights.filter { it < 0L }.sum(), s.objectiveBoolLowerBound(), "root = Σ min(w,0)")
    }

    @Test
    fun `install is declined below the root so the caller rescans`() {
        val p = Problem(2, 0, emptyArray(), emptyList())
        val s = PropagationSession(p)
        s.seed(Assumptions.None)
        s.pinBool(0, true) // now at decision level 1
        assertFalse(s.installObjectiveBoolBound(longArrayOf(3, 4)), "install must decline off the root")
    }
}
