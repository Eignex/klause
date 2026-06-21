package com.eignex.klause.solver.factor.scheduling

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.scheduling.Diffn
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffnInvariantTest {

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
            val before = state.factors[0].violationDegree(state, 0)
            val predicted = state.factors[0].deltaIfIntSet(state, 0, v, nv)
            state.apply(Move.IntSet(v, nv))
            val after = state.factors[0].violationDegree(state, 0)
            assertEquals(after - before, predicted, "step $step: set v=$v to $nv")
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
}
