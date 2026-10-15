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
    fun `COS should pick the most recently conflicting var`() {
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
    fun `COS should replay conflict order in reverse after a pin removes the top`() {
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
        session.pinInt(1, 0)
        assertEquals(VarRef.IntVar(2), cos.pick(session, Random(0L)))
    }

    @Test
    fun `COS should stamp conflict-graph vars from the unsat reason`() {
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
        session.pinInt(3, 0)
        val picked = cos.pick(session, Random(0L))
        assertTrue(
            picked == VarRef.IntVar(0) || picked == VarRef.IntVar(2),
            "should pick a stamped conflict-graph var; got $picked",
        )
    }
}
