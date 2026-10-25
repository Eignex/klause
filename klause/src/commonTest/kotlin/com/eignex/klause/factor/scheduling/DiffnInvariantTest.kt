package com.eignex.klause.factor.scheduling

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffnInvariantTest {

    @Test
    fun `primary repair should relocate past a rectangle outside the colliding pair`() {
        val variants = listOf(false, true).flatMap { vertical ->
            listOf(false, true).map { primary -> vertical to primary }
        }
        for ((vertical, primary) in variants) {
            val positions = intArrayOf(0, 1, 2)
            val fixed = intArrayOf(3, 4, 5)
            val factor = Diffn(
                xs = if (vertical) fixed else positions,
                ys = if (vertical) positions else fixed,
                widths = LongArray(3) { if (vertical) 1L else 2L },
                heights = LongArray(3) { if (vertical) 2L else 1L },
            )
            val problem = Problem(0, 6, Array(6) { IntDomain(0, if (it < 3) 5 else 0) }, listOf(factor))
            val state = LocalSearchState(problem.bake(), Random(7L))
            state.assignment.setInt(1, 1L)
            state.assignment.setInt(2, 3L)
            state.recompute()
            val sink = MoveSink()
            if (primary) sink.alternativeRepairs = MoveSink()

            state.factors[0].proposeRepairMoves(state, 0, sink)

            assertEquals(primary, sink.list.any { state.netDelta(it) == -state.cost },
                "vertical=$vertical primary=$primary")
        }
    }

    // Probing and apply share the affected-pair formula, so only a full recount can catch a shared error.
    private fun assertDeltaMatchesRecompute(
        numIntVars: Int,
        domains: Array<IntDomain>,
        factor: Diffn,
        probeVars: IntArray,
        valueRange: IntRange,
        seed: Int,
    ) {
        val problem = Problem(0, numIntVars, domains, listOf(factor))
        val state = LocalSearchState(problem.bake(), Random(seed.toLong()))
        state.recompute()
        val rng = Random(seed * 31L + 1)
        repeat(600) { step ->
            val v = probeVars[rng.nextInt(probeVars.size)]
            val nv = rng.nextInt(valueRange.first, valueRange.last + 1).toLong()
            val before = state.factors[0].violationDegree(state, 0)
            val predicted = state.factors[0].deltaIfIntSet(state, 0, v, nv)
            state.apply(Move.IntSet(v, nv))
            val after = state.factors[0].violationDegree(state, 0)
            assertEquals(after - before, predicted, "step $step: set v=$v to $nv")
        }
    }

    @Test
    fun `deltaIfIntSet should match full recompute with constant sizes`() {
        assertDeltaMatchesRecompute(
            numIntVars = 8,
            domains = Array(8) { IntDomain(0, 4) },
            factor = Diffn(
                xs = intArrayOf(0, 1, 2, 3),
                ys = intArrayOf(4, 5, 6, 7),
                widths = longArrayOf(2, 1, 2, 1),
                heights = longArrayOf(1, 2, 2, 1),
            ),
            probeVars = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
            valueRange = 0..4,
            seed = 7,
        )
    }

    @Test
    fun `deltaIfIntSet should match full recompute with variable sizes`() {
        val domains = Array(16) { if (it < 8) IntDomain(0, 4) else IntDomain(1, 3) }
        assertDeltaMatchesRecompute(
            numIntVars = 16,
            domains = domains,
            factor = Diffn(
                xs = intArrayOf(0, 1, 2, 3),
                ys = intArrayOf(4, 5, 6, 7),
                widths = LongArray(4),
                heights = LongArray(4),
                widthVars = intArrayOf(8, 9, 10, 11),
                heightVars = intArrayOf(12, 13, 14, 15),
            ),
            probeVars = IntArray(16) { it },
            valueRange = 1..3,
            seed = 13,
        )
    }
}
