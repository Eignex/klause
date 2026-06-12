package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.Clause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Glucose-style adaptive restarts (#198): the [GlucoseRestart] policy restarts when recent
 * learned-clause LBD runs hot relative to the long-run average, unless trail-size blocking
 * defers it. These tests pin the trigger and blocking logic directly, then confirm the engine
 * still produces correct verdicts and a complete model set with adaptive restarts enabled.
 */
class AdaptiveRestartTest {

    @Test
    fun `policy restarts when the recent LBD window runs hotter than the global average`() {
        val g = GlucoseRestart(lbdWindow = 4, trailWindow = 1000, restartMargin = 0.8, blockingFactor = 1.4)
        // Warm up a low global average with good (low-LBD) clauses; trail stays small so the
        // blocking window (capacity 1000) never fills.
        repeat(20) { assertFalse(g.recordConflict(lbd = 2, trailSize = 10)) }
        // Now the solver starts learning poor (high-LBD) clauses: the recent window heats up
        // above the long-run average and a restart must fire.
        var fired = false
        repeat(20) { if (g.recordConflict(lbd = 20, trailSize = 10)) fired = true }
        assertTrue(fired, "sustained high recent LBD must force a restart")
    }

    @Test
    fun `trail-size blocking suppresses a restart that LBD would otherwise trigger`() {
        fun warmedPolicy(): GlucoseRestart {
            val g = GlucoseRestart(lbdWindow = 4, trailWindow = 4, restartMargin = 0.8, blockingFactor = 1.4)
            // Fill both windows with low-LBD, small-trail conflicts → low global LBD average.
            repeat(20) { g.recordConflict(lbd = 2, trailSize = 10) }
            return g
        }
        // A hot-LBD conflict with a normal trail: the recent window is hotter than the global
        // average, so the restart fires.
        assertTrue(warmedPolicy().recordConflict(lbd = 50, trailSize = 10), "hot LBD should restart")
        // The same hot-LBD conflict but with a trail spike well above the recent average: the
        // solver is driving deep toward a model, so blocking defers the restart.
        assertFalse(warmedPolicy().recordConflict(lbd = 50, trailSize = 100), "trail spike must block")
    }

    /** Pigeonhole P(n+1, n): the classic UNSAT clause family. */
    private fun pigeonhole(pigeons: Int, holes: Int): Problem {
        val factors = ArrayList<Factor>()
        fun v(p: Int, h: Int) = p * holes + h
        for (p in 0 until pigeons) factors.add(Clause(IntArray(holes) { h -> Lit.make(v(p, h), true) }))
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
    fun `adaptive restarts prove a clause-dense unsat instance`() {
        val verdict = BacktrackSolver(pigeonhole(pigeons = 4, holes = 3)).solve(
            BacktrackParams(
                randomSeed = 1L,
                variableHeuristic = Vsids(),
                adaptiveRestart = true,
                maxLearnedClauses = 5_000,
            ),
        )
        assertIs<SolveResult.Unsat>(verdict)
    }

    private fun clauseProblem(): Problem = Problem(
        numBoolVars = 5,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(3, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(4, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(3, false), Lit.make(4, false))),
        ),
    )

    @Test
    fun `adaptive restarts enumerate exactly the same models as no restarts`() {
        fun models(params: BacktrackParams): Set<List<Boolean>> =
            BacktrackSolver(clauseProblem()).enumerate(params).map { it.bools.toList() }.toSet()

        val plain = models(BacktrackParams(randomSeed = 5L, variableHeuristic = Vsids()))
        val adaptive = models(
            BacktrackParams(randomSeed = 5L, variableHeuristic = Vsids(), adaptiveRestart = true),
        )
        assertTrue(plain.isNotEmpty())
        assertEquals(plain, adaptive, "adaptive restarts must not change the feasible set")
    }
}
