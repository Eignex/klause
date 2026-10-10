package com.eignex.klause.backtrack.selector

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictOrderingTest {

    @Test
    fun `COS picks the most recently conflicting var`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 4) },
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val cos = ConflictOrdering(InputOrder)
        cos.onConflict(VarRef.IntVar(2))
        cos.onConflict(VarRef.IntVar(1))
        cos.onConflict(VarRef.IntVar(3))
        assertEquals(VarRef.IntVar(3), cos.pick(session, Random(0L)))
    }

    @Test
    fun `COS replays conflict order in reverse after a pin removes the top`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 4) },
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val cos = ConflictOrdering(InputOrder)
        cos.onConflict(VarRef.IntVar(0))
        cos.onConflict(VarRef.IntVar(2))
        cos.onConflict(VarRef.IntVar(1))
        // Pin var 1; pick should now return var 2 (next-most-recent).
        session.pinInt(1, 0)
        assertEquals(VarRef.IntVar(2), cos.pick(session, Random(0L)))
    }

    @Test
    fun `COS stamps conflict-graph vars from unsat reason`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 4) },
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val cos = ConflictOrdering(InputOrder)
        val unsat = PropagationResult.Unsat(conflictInts = intArrayOf(0, 2, 3))
        cos.onConflict(VarRef.IntVar(3), unsat)
        // Pin var 3 and verify pick returns one of {0, 2} (stamped via unsat reason set).
        session.pinInt(3, 0)
        val picked = cos.pick(session, Random(0L))
        assertTrue(
            picked == VarRef.IntVar(0) || picked == VarRef.IntVar(2),
            "should pick a stamped conflict-graph var; got $picked",
        )
    }
}
