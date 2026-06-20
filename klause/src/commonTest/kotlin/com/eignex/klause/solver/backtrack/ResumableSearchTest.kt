package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.arithmetic.Linear
import com.eignex.klause.solver.factor.arithmetic.LinearOp
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pause/resume for the backtrack engine (#381): [com.eignex.klause.solver.ResumableSearch] must hold
 * the entire search state explicitly so a slice-truncated segment resumes the exact search mid-proof
 * — not restart it — and reach the same verdict a one-shot solve would.
 *
 * The pause is driven by a **decision-counting** token (the engine polls cancellation every
 * `CANCEL_CHECK_INTERVAL` decisions), not wall-clock, so the multi-slice behaviour is deterministic.
 */
class ResumableSearchTest {

    /** Pigeonhole PHP(p, p-1): `p` pigeons each in exactly one of `p-1` holes, at most one pigeon per
     *  hole — infeasible, and its UNSAT proof needs far more than one cancel-check interval of search. */
    private fun pigeonhole(pigeons: Int, holes: Int): Problem {
        fun v(p: Int, h: Int) = p * holes + h
        val factors = ArrayList<Factor>()
        for (p in 0 until pigeons) {
            factors.add(Cardinality.exactlyOne(IntArray(holes) { h -> Lit.make(v(p, h), true) }))
        }
        for (h in 0 until holes) {
            factors.add(Cardinality.atMostOne(IntArray(pigeons) { p -> Lit.make(v(p, h), true) }))
        }
        return Problem(
            numBoolVars = pigeons * holes,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = factors.toTypedArray(),
        )
    }

    /** Drive a fresh handle to completion, pausing after the first cancel-check of every slice (so the
     *  search is forced to resume), and return the terminal verdict plus how many slices it took. */
    private fun driveInSlices(
        problem: Problem,
        objective: LinearObjective,
        onIncumbent: (Double) -> Unit = {},
    ): Pair<MinimizeResult, Int> {
        val handle = BacktrackSolver(problem).resumable(objective, BacktrackParams(randomSeed = 0L))
        var slices = 0
        var terminal: MinimizeResult? = null
        while (terminal == null && slices < 1_000_000) {
            // false on this slice's first poll, true thereafter → ~one cancel-check interval per slice.
            var checks = 0
            val pauseAfterOnePoll = Cancellation { checks++ > 0 }
            terminal = handle.runSlice(pauseAfterOnePoll, sliceMillis = 60_000) { onIncumbent(it.objectiveValue) }
            slices++
        }
        assertNotNull(terminal, "the sliced search must terminate")
        assertTrue(handle.isDone, "handle is done once it returns a terminal verdict")
        return terminal to slices
    }

    @Test
    fun `a paused-and-resumed UNSAT proof reaches Infeasible across many slices`() {
        val problem = pigeonhole(pigeons = 7, holes = 6)
        val obj = LinearObjective(boolWeights = LongArray(problem.numBoolVars) { if (it == 0) 1L else 0L })

        // One-shot reference.
        assertIs<MinimizeResult.Infeasible>(BacktrackSolver(problem).minimize(obj, BacktrackParams(randomSeed = 0L)))

        // Sliced: the proof spans multiple slices, so the engine genuinely paused and resumed mid-tree
        // (a cold restart each slice would re-derive everything and never converge here).
        val (terminal, slices) = driveInSlices(problem, obj)
        assertIs<MinimizeResult.Infeasible>(terminal)
        assertTrue(slices > 1, "the UNSAT proof must pause and resume at least once (slices=$slices)")
    }

    @Test
    fun `a paused-and-resumed branch-and-bound reaches the same optimum as a one-shot minimize`() {
        // The knapsack from CdclOptimizationTest: 7 items, maximise value within a weight cap, posed as
        // minimise Σ(−value)·x. Resume must carry the incumbent + learned clauses to prove the optimum.
        val weights = intArrayOf(3, 4, 5, 2, 6, 1, 4)
        val values = intArrayOf(5, 6, 8, 3, 9, 2, 5)
        val cap = 12
        val n = weights.size
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(
                Linear(coeffs = weights, vars = IntArray(n) { it }, op = LinearOp.LE, bound = cap),
            ),
        )
        val obj = LinearObjective(intCoefficients = LongArray(n) { -values[it].toLong() })

        val oneShot = assertIs<MinimizeResult.Optimal>(
            BacktrackSolver(problem).minimize(obj, BacktrackParams(randomSeed = 0L)),
        )

        val seen = ArrayList<Double>()
        val (terminal, _) = driveInSlices(problem, obj, onIncumbent = { seen.add(it) })
        val optimal = assertIs<MinimizeResult.Optimal>(terminal)
        assertEquals(oneShot.objective, optimal.objective, "sliced resume must prove the same optimum")
        // The incumbent improves monotonically across whatever slices it took to land it.
        for (i in 1 until seen.size) assertTrue(seen[i] < seen[i - 1], "incumbents must strictly improve")
    }

    @Test
    fun `once done a handle returns the same terminal without further work`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(
                Linear(coeffs = intArrayOf(1, 1), vars = intArrayOf(0, 1), op = LinearOp.GE, bound = 3),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 2L))
        val handle = BacktrackSolver(problem).resumable(obj, BacktrackParams(randomSeed = 0L))
        val first = handle.runSlice(Cancellation.Never, sliceMillis = 60_000) {}
        assertIs<MinimizeResult.Optimal>(first)
        assertEquals(3.0, first.objectiveValue)
        // A subsequent call returns the cached verdict (idempotent once done).
        val again = handle.runSlice(Cancellation.Never, sliceMillis = 60_000) {}
        assertEquals(first, again)
    }
}
