package com.eignex.klause.solver.propagation

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

        for (lit in state.boolWatchersByLit.indices) {
            assertEquals(
                state.boolWatchersByLit[lit].size,
                state.boolBlockersByLit[lit].size,
                "watcher and blocker lists must stay aligned for lit $lit",
            )
        }
    }

    /** Pigeonhole P(n+1, n): place n+1 pigeons in n holes, at most one pigeon per hole — the
     *  classic UNSAT clause family. Variable `p*n + h` means "pigeon p sits in hole h". */
    private fun pigeonhole(pigeons: Int, holes: Int): Problem {
        val factors = ArrayList<Factor>()
        fun v(p: Int, h: Int) = p * holes + h
        // Each pigeon occupies at least one hole.
        for (p in 0 until pigeons) {
            factors.add(Clause(IntArray(holes) { h -> Lit.make(v(p, h), true) }))
        }
        // No hole holds two pigeons: for each hole and each pigeon pair, ¬p1h ∨ ¬p2h.
        for (h in 0 until holes) {
            for (p1 in 0 until pigeons) {
                for (p2 in p1 + 1 until pigeons) {
                    factors.add(Clause(intArrayOf(Lit.make(v(p1, h), false), Lit.make(v(p2, h), false))))
                }
            }
        }
        return Problem(
            numBoolVars = pigeons * holes,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = factors.toTypedArray(),
        )
    }

    @Test
    fun `clause-dense unsat pigeonhole still proves unsat under blocking literals`() {
        val verdict = BacktrackSolver(pigeonhole(pigeons = 4, holes = 3)).solve(
            BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 5_000),
        )
        assertIs<SolveResult.Unsat>(verdict)
    }

    @Test
    fun `clause-dense satisfiable pigeonhole finds a valid placement`() {
        val holes = 4
        val pigeons = 4 // P(4,4) is satisfiable: a perfect matching exists.
        val sat = assertIs<SolveResult.Sat>(
            BacktrackSolver(pigeonhole(pigeons, holes)).solve(
                BacktrackParams(randomSeed = 2L, variableSelector = Vsids(), maxLearnedClauses = 5_000),
            ),
        )
        val b = sat.assignment.bools
        // Each pigeon in at least one hole; no hole shared.
        val holeUsed = BooleanArray(holes)
        for (p in 0 until pigeons) {
            var placed = 0
            for (h in 0 until holes) {
                if (b[p * holes + h]) {
                    placed++
                    assertTrue(!holeUsed[h], "hole $h used twice")
                    holeUsed[h] = true
                }
            }
            assertTrue(placed >= 1, "pigeon $p unplaced")
        }
    }
}
