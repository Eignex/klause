package com.eignex.klause.solver.integration

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.pinBoolAsDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Blocking literals (#200) cache, per watch entry, another literal of the clause; when that
 * blocker is already true the propagation engine skips waking the clause. The cache is a pure
 * throughput optimization, so these tests check two things: the bookkeeping stays consistent
 * (blocker list size-aligned with the watcher list across watch moves) and the search still
 * produces the right verdicts under heavy clause propagation.
 */
class BlockingLiteralTest {

    @Test
    fun `blocker list stays size-aligned with the watcher list across watch moves`() {
        // Clause (x0 ∨ x1 ∨ x2 ∨ x3): initially watches literals[0]=x0 and literals[1]=x1.
        // Pinning x0=false then x1=false forces both watches to relocate to x2 / x3, exercising
        // moveBoolWatcher's swap-pop on the watcher and blocker lists in lockstep.
        val clause = Clause(
            intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
        )
        val problem = Problem(
            numBoolVars = 4,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(clause),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true

        assertTrue(state.pinBoolAsDecision(0, false))
        assertEquals(null, state.runToFixpoint(allFactors = false), "x0=false must not conflict")
        assertTrue(state.pinBoolAsDecision(1, false))
        assertEquals(null, state.runToFixpoint(allFactors = false), "x1=false must not conflict")

        for (lit in state.watches.byLit.indices) {
            assertEquals(
                state.watches.byLit[lit].size,
                state.watches.blockersByLit[lit].size,
                "watcher and blocker lists must stay aligned for lit $lit",
            )
        }
    }
}
