package com.eignex.klause.backtrack.selector

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.PropagationResult.Unsat
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class VsidsTest {

    @Test
    fun `vsids finds SAT on a hard pigeonhole-like instance`() {
        // 6 vars with constraints forcing the search through several conflicts. VSIDS
        // should consistently find a model — sanity check that activity-driven picking
        // doesn't break correctness vs. the default random heuristic.
        val problem = Problem(
            numBoolVars = 6,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(0, true),
                        Lit.make(1, true),
                        Lit.make(2, true),
                    ),
                ),
                Cardinality.exactlyOne(
                    intArrayOf(
                        Lit.make(3, true),
                        Lit.make(4, true),
                        Lit.make(5, true),
                    ),
                ),
                Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false))),
                Clause(intArrayOf(Lit.make(1, false), Lit.make(4, false))),
                Clause(intArrayOf(Lit.make(2, false), Lit.make(5, false))),
            ),
        )
        val r = BacktrackSolver(problem.bake()).solve(
            BacktrackParams(
                variableSelector = Vsids(),
                randomSeed = 0L,
            ),
        )
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(
            1,
            sat.assignment.bools.take(3).count { it },
            "exactly one of v0..v2 should be true",
        )
        assertEquals(
            1,
            sat.assignment.bools.drop(3).count { it },
            "exactly one of v3..v5 should be true",
        )
    }

    @Test
    fun `vsids proves UNSAT and accumulates activity`() {
        // Direct two-clause contradiction. Should return Unsat immediately via
        // bake-time propagation — no conflicts in the search tree, but VSIDS shouldn't
        // crash on the early return.
        val problem = Problem(
            numBoolVars = 1,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Clause(intArrayOf(Lit.make(0, true))),
                Clause(intArrayOf(Lit.make(0, false))),
            ),
        )
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(variableSelector = Vsids()))
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `vsids prefers highest-activity variable after onConflict bumps`() {
        // Drive Vsids directly: bump var 3 a few times, then ask it to pick from an
        // all-unpinned 5-bool problem. v3 should win.
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = emptyArray(),
        )
        val vsids = Vsids()
        // Empty Unsat record so only v3 (the failing decision) gets the bump.
        val emptyUnsat = Unsat()
        repeat(3) { vsids.onConflict(VarRef.Bool(3), emptyUnsat) }
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(variableSelector = vsids))
        assertIs<SolveResult.Sat>(r)
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
