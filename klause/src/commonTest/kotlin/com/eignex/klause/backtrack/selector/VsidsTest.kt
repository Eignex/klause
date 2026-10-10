package com.eignex.klause.backtrack.selector

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult.Unsat
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.search.VarRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class VsidsTest {

    @Test
    fun `vsids prefers highest-activity variable after onConflict bumps`() {
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val vsids = Vsids()
        val session = PropagationSession(problem)
        session.seed(Assumptions.None)
        // onConflict is a no-op before the heap exists; size it with a throwaway pick first.
        vsids.pick(session, Random(0L))
        // Empty Unsat record so only v3 (the failing decision) gets the bump.
        val emptyUnsat = Unsat()
        repeat(3) { vsids.onConflict(VarRef.Bool(3), emptyUnsat) }
        assertEquals(VarRef.Bool(3), vsids.pick(session, Random(0L)))
    }

    @Test
    fun `vsids resizes activity arrays across problems`() {
        // A single Vsids instance reused across two problems with different shapes
        // should resize cleanly without crashing.
        val vsids = Vsids()
        val p1 = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true)))),
        )
        val r1 = BacktrackSolver(p1.bake()).solve(BacktrackParams(variableSelector = vsids))
        assertIs<SolveResult.Sat>(r1)

        val p2 = Problem(
            numBoolVars = 7,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(6, true)))),
        )
        val r2 = BacktrackSolver(p2.bake()).solve(BacktrackParams(variableSelector = vsids))
        assertIs<SolveResult.Sat>(r2)
        assertEquals(true, r2.assignment.bools[6])
    }

    @Test
    fun `pick re-offers an open variable stranded out of the heap`() {
        // The pop-on-pick scheme drops a variable from the heap when it surfaces assigned, relying on
        // onUnassign to re-add it on backtrack. If that re-add is missed (as happens under the
        // portfolio's clause exchange), an open variable is left out of the heap. pick must still
        // re-offer it — returning null would tell the engine "all assigned" and let it emit an
        // incomplete assignment as a (unsound) solution.
        val problem = Problem(0, 1, arrayOf(IntDomain(0, 3)), emptyArray())
        val session = PropagationSession(problem)
        session.seed(Assumptions.None)
        val vsids = Vsids()
        val rng = Random(0)
        session.pinInt(0, 2) // x = 2 (singleton): x surfaces assigned and is dropped from the heap
        assertNull(vsids.pick(session, rng))
        session.popLast() // x widens back to [0, 3]; no unassign listener is wired, so the re-add is missed
        assertEquals(VarRef.IntVar(0), vsids.pick(session, rng), "pick must re-offer the stranded open variable")
    }
}
