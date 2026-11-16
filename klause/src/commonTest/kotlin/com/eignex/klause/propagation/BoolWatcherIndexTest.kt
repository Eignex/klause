package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.klause.factor.litVars
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoolWatcherIndexTest {
    @Test
    fun `paired retirement should preserve blockers and moved learned positions`() {
        val literals = IntArray(2) { Lit.make(it, true) }
        val problem = Problem(2, 0, emptyArray(), arrayOf<Factor>(Clause(literals)))
        val state = PropagationState(problem, Assumptions.None)
        val learned = ClausePropagator(literals.litVars(), IntArray(0), literals.copyOf())
        val factor = state.addLearnedClause(learned, 2)
        val blockers = literals.map { state.watches.blockersByLit[it][1] }

        assertTrue(state.removeInlineBoolWatches(0, true))

        for (i in literals.indices) {
            val literal = literals[i]
            assertEquals(1, state.watches.byLit[literal].size)
            assertEquals(factor, state.watches.byLit[literal][0])
            assertEquals(blockers[i], state.watches.blockersByLit[literal][0])
            assertEquals(0, state.watches.pos.getOrDefault(state.packWatch(factor, literal), -1))
            assertEquals(-1, state.watches.pos.getOrDefault(state.packWatch(0, literal), -1))
        }
        state.removeBoolWatch(factor, literals[0])
        state.removeBoolWatch(factor, literals[1])
        assertEquals(0, state.watches.pos.size)
        for (literal in literals) {
            assertEquals(0, state.watches.byLit[literal].size)
            assertEquals(0, state.watches.blockersByLit[literal].size)
        }
    }


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
