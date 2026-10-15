package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoolWatcherIndexTest {

    @Test
    fun `blocker lists should stay aligned with watcher lists across watch moves`() {
        val clause = Clause(IntArray(4) { Lit.make(it, true) })
        val problem = Problem(4, 0, emptyArray(), arrayOf<Factor>(clause))
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true

        assertTrue(state.pinBoolAsDecision(0, false))
        assertEquals(null, state.runToFixpoint(allFactors = false))
        assertTrue(state.pinBoolAsDecision(1, false))
        assertEquals(null, state.runToFixpoint(allFactors = false))

        for (lit in state.watches.byLit.indices) {
            assertEquals(state.watches.byLit[lit].size, state.watches.blockersByLit[lit].size)
        }
    }
}
