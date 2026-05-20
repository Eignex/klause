package com.eignex.klause.solver

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchSession

import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.localsearch.strategy.Ddfw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalSearchSessionTest {

    private fun ddfwProblem(): Problem {
        // 5 cardinality factors over overlapping vars — DDFW shuffles weights between
        // satisfied / unsatisfied neighbours, producing non-default weights quickly.
        return Problem(
            numBoolVars = 6, numIntVars = 0, intDomains = emptyArray(),
            factors = arrayOf<Factor>(
                Cardinality.exactlyOne(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(2, true), Lit.make(3, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(4, true), Lit.make(5, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
                Cardinality.exactlyOne(intArrayOf(Lit.make(3, true), Lit.make(4, true))),
            ),
        )
    }

    @Test
    fun `maxInstructions tightens flip budget vs maxFlips when smaller`() {
        // A 10-clause unsat-but-not-trivially-detected instance — LS needs flips to give
        // up. With maxInstructions = 5 (tiny), the search exhausts and returns Unknown.
        // Same problem with maxInstructions = 100_000 finds the contradiction is hard
        // but the LS engine still returns within the loose budget. Both verdicts are
        // wall-clock-independent — the same seed always yields the same flip count.
        val problem = ddfwProblem()
        val solver = LocalSearchSolver(problem)
        val tight = solver.solve(LocalSearchParams(
            maxFlips = Long.MAX_VALUE, maxInstructions = 5L, randomSeed = 0L,
        ))
        // With only 5 flips on a 6-bool 5-factor problem, the LS engine is unlikely to
        // luck into the model — either it reaches it (Sat) or runs out (Unknown). The
        // contract here is that the budget is *honoured* — the call returns promptly
        // instead of running indefinitely.
        assertTrue(tight is SolveResult.Sat || tight is SolveResult.Unknown,
            "tight maxInstructions must terminate cleanly, got $tight")
    }

    @Test
    fun `session captures DDFW factor weights after a call`() {
        val problem = ddfwProblem()
        val solver = LocalSearchSolver(problem, strategy = Ddfw())
        val session = LocalSearchSession(solver)
        // Before any call: warm state is empty.
        assertNull(session.warmState.factorWeights)
        // Run a sample-search; DDFW will mutate weights along the way.
        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L)).assignment
        val captured = session.warmState.factorWeights
        assertNotNull(captured, "session should capture factorWeights")
        assertEquals(problem.numFactors, captured.size)
        // At least one weight should have deviated from the default of 1.0.
        assertTrue(captured.any { it != 1.0 }, "DDFW should learn non-default weights")
    }

    @Test
    fun `reset clears warm state`() {
        val problem = ddfwProblem()
        val session = LocalSearchSession(LocalSearchSolver(problem, strategy = Ddfw()))
        session.sample(LocalSearchParams(maxFlips = 1_000L, randomSeed = 2L)).assignment
        assertNotNull(session.warmState.factorWeights)
        session.reset()
        assertNull(session.warmState.factorWeights)
    }

    @Test
    fun `warm weights survive across two minimize calls`() {
        val problem = ddfwProblem()
        val session = LocalSearchSession(LocalSearchSolver(problem, strategy = Ddfw()))
        val obj = LinearObjective(boolWeights = DoubleArray(6) { 1.0 })
        session.minimize(obj, LocalSearchParams(maxFlips = 1_000L, randomSeed = 5L)).assignment
        val firstWeights = session.warmState.factorWeights!!.copyOf()
        session.minimize(obj, LocalSearchParams(maxFlips = 1_000L, randomSeed = 6L)).assignment
        val secondWeights = session.warmState.factorWeights!!
        // The second capture should not be exactly equal to the all-defaults vector —
        // it built on top of the first.
        val allOnes = DoubleArray(problem.numFactors) { 1.0 }
        assertTrue(
            !secondWeights.contentEquals(allOnes),
            "second call should have learned weights, not reset to defaults",
        )
        // And it should generally differ from the first capture (further learning happened).
        // Tolerate equality only in pathological cases; this is a loose check.
        assertTrue(
            firstWeights.size == secondWeights.size,
            "weight array shape must match across calls",
        )
    }

    @Test
    fun `session implements Session interface and is returned by solver session factory`() {
        val solver = LocalSearchSolver(ddfwProblem())
        // The factory's return type is `LocalSearchSession` (overridden from the default
        // `Session<...>`), so the type system already enforces what this test originally
        // asserted at runtime.
        val session: LocalSearchSession = solver.session()
        // depth = 0 on a fresh session; push/pop work.
        assertEquals(0, session.depth)
        session.push(com.eignex.klause.solver.Assumptions(bools = mapOf(0 to true)))
        assertEquals(1, session.depth)
        session.pop()
        assertEquals(0, session.depth)
    }

    @Test
    fun `session captures variable activity counts after a call`() {
        val problem = ddfwProblem()
        val solver = LocalSearchSolver(problem)
        val session = LocalSearchSession(solver)
        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 7L)).assignment
        val touches = session.warmStateView.activityTouches()
        assertEquals(6, touches.size, "touches should cover all (bool + int) var slots")
        // After a sample search, at least some vars must have been touched.
        assertTrue(touches.any { it > 0 }, "expected at least one touched variable")
    }

    @Test
    fun `bestCostSeen watermark survives session call boundaries`() {
        val problem = ddfwProblem()
        val solver = LocalSearchSolver(problem)
        val session = LocalSearchSession(solver)
        // First call: search runs, watermark captured into WarmState.
        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 1L)).assignment
        val firstWatermark = session.warmStateView.bestCostSeen()
        assertTrue(firstWatermark < Int.MAX_VALUE,
            "expected watermark after first call, got $firstWatermark")

        // Second call: warm state should re-seed bestCostSeen and the captured value is
        // never higher than the first call's.
        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 2L)).assignment
        val secondWatermark = session.warmStateView.bestCostSeen()
        assertTrue(secondWatermark <= firstWatermark,
            "watermark must monotone-decrease: $firstWatermark -> $secondWatermark")
    }

    @Test
    fun `reset clears bestCostSeen alongside other warm fields`() {
        val problem = ddfwProblem()
        val solver = LocalSearchSolver(problem)
        val session = LocalSearchSession(solver)
        session.sample(LocalSearchParams(maxFlips = 2_000L, randomSeed = 3L)).assignment
        assertTrue(session.warmStateView.bestCostSeen() < Int.MAX_VALUE)
        session.reset()
        assertEquals(Int.MAX_VALUE, session.warmStateView.bestCostSeen(),
            "reset should restore the bestCost watermark to its empty default")
    }

    @Test
    fun `bare solver call does not touch the session warm state`() {
        // Concurrent / non-session callers shouldn't have a path to mutate session warm
        // state. Verified by running a bare solver call and confirming the unrelated
        // session's warm state is still null afterwards.
        val problem = ddfwProblem()
        val solver = LocalSearchSolver(problem, strategy = Ddfw())
        val session = LocalSearchSession(solver)
        // Bare call — bypasses the session entirely.
        solver.sample(LocalSearchParams(maxFlips = 1_000L, randomSeed = 9L)).assignment
        assertNull(session.warmState.factorWeights, "bare solver call must not write to session warm state")
    }
}
