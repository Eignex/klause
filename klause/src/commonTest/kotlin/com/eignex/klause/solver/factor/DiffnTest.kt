package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DiffnTest {

    /** Drives [Diffn.deltaIfIntSet] (the affected-pair fast path) against ground truth: for a
     *  stream of random single-var moves, the predicted violation delta must equal the
     *  transition observed after [LocalSearchState.apply], whose [Diffn.applyIntSet] does an
     *  exact full recount. Guards the #96 incremental delta. */
    private fun assertDeltaMatchesRecompute(
        numIntVars: Int,
        domains: Array<IntDomain>,
        factor: Diffn,
        probeVars: IntArray,
        valueRange: IntRange,
        seed: Int,
    ) {
        val problem = Problem(0, numIntVars, domains, listOf(factor))
        val state = LocalSearchState(problem, Random(seed.toLong()))
        state.recompute()
        val rng = Random(seed * 31L + 1)
        repeat(600) { step ->
            val v = probeVars[rng.nextInt(probeVars.size)]
            val nv = rng.nextInt(valueRange.first, valueRange.last + 1)
            val was = factor.isViolated(state, 0)
            val predicted = factor.deltaIfIntSet(state, 0, v, nv)
            state.apply(Move.IntSet(v, nv))
            val now = factor.isViolated(state, 0)
            val expected = (if (now) 1 else 0) - (if (was) 1 else 0)
            assertEquals(expected, predicted, "step $step: set v=$v to $nv")
        }
    }

    @Test
    fun `deltaIfIntSet matches full recompute - constant sizes`() {
        assertDeltaMatchesRecompute(
            numIntVars = 8,
            domains = Array(8) { IntDomain(0, 4) },
            factor = Diffn(
                xs = intArrayOf(0, 1, 2, 3),
                ys = intArrayOf(4, 5, 6, 7),
                widths = intArrayOf(2, 1, 2, 1),
                heights = intArrayOf(1, 2, 2, 1),
            ),
            probeVars = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
            valueRange = 0..4,
            seed = 7,
        )
    }

    @Test
    fun `deltaIfIntSet matches full recompute - variable sizes`() {
        // Positions in ids 0..7 (domain [0,4]); widths in 8..11, heights in 12..15 ([1,3]).
        val domains = Array(16) { if (it < 8) IntDomain(0, 4) else IntDomain(1, 3) }
        assertDeltaMatchesRecompute(
            numIntVars = 16,
            domains = domains,
            factor = Diffn(
                xs = intArrayOf(0, 1, 2, 3),
                ys = intArrayOf(4, 5, 6, 7),
                widths = IntArray(4),
                heights = IntArray(4),
                widthVars = intArrayOf(8, 9, 10, 11),
                heightVars = intArrayOf(12, 13, 14, 15),
            ),
            probeVars = IntArray(16) { it },
            valueRange = 1..3, // within both the position [0,4] and size [1,3] domains
            seed = 13,
        )
    }

    @Test
    fun `non-overlapping packing exists`() {
        // Two 2x2 rectangles in a 3x3 grid → only configurations where they share a
        // corner-line; we just verify Sat exists.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(
                Diffn(
                    xs = intArrayOf(0, 2),
                    ys = intArrayOf(1, 3),
                    widths = intArrayOf(2, 2),
                    heights = intArrayOf(2, 2),
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        // 2x2 + 2x2 in [0,1]x[0,1] positions only — actually no valid placement; expect Unsat.
        // Wait — positions can be (0,0), (0,1), (1,0), (1,1) but rect spans 2 units. Two
        // 2x2 rectangles can never fit in a 3x3 grid (3x3 area = 9, 2x2x2 = 8 ok). With
        // positions ∈ [0,1]: at (0,0) covers [0,2)x[0,2); at (1,1) covers [1,3)x[1,3) →
        // overlap at [1,2)x[1,2). At (0,1) and (1,0): also overlap. No non-overlap.
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `two unit squares pinned non-overlapping is Sat`() {
        // Rect 1 at (0,0) 1x1. Rect 2 at (1,0) 1x1. Disjoint.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 0), IntDomain(1, 1), IntDomain(0, 0)),
            factors = arrayOf<Factor>(
                Diffn(
                    xs = intArrayOf(0, 2),
                    ys = intArrayOf(1, 3),
                    widths = intArrayOf(1, 1),
                    heights = intArrayOf(1, 1),
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        assertIs<SolveResult.Sat>(r)
    }

    @Test
    fun `overlapping pinned rectangles is Unsat`() {
        // Both rectangles pinned at (0, 0), 2x2 each → identical, overlap.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 0) },
            factors = arrayOf<Factor>(
                Diffn(
                    xs = intArrayOf(0, 2),
                    ys = intArrayOf(1, 3),
                    widths = intArrayOf(2, 2),
                    heights = intArrayOf(2, 2),
                ),
            ),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L)))
    }
}
