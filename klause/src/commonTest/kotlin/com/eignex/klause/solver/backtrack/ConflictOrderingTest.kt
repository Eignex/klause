package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConflictOrderingTest {

    @Test
    fun `COS delegates to base when no conflicts have happened yet`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 4) },
            factors = emptyArray(),
        )
        val session = PropagationSession(problem)
        val cos = ConflictOrdering(InputOrder)
        assertEquals(VarRef.IntVar(0), cos.pick(session, Random(0L)))
    }

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
        // Most recent stamp is on var 3.
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

    @Test
    fun `COS still solves`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2, 3, 4), domainMin = 0, domainSize = 5)),
        )
        val r = BacktrackSolver(problem).solve(
            BacktrackParams(
                variableHeuristic = ConflictOrdering(DomWdeg()),
                valueHeuristic = IndomainMin,
                randomSeed = 0L,
            ),
        )
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals((0..4).toSet(), sat.assignment.ints.toSet())
    }
}
