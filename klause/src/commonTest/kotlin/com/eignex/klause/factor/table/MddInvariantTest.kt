package com.eignex.klause.factor.table

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MddInvariantTest {

    @Test
    fun `weighted repeated coordinates should retain exact scores across compound edits and restoration`() {
        val records = ArrayList<Long>()
        val starts = IntArray(5)
        for (layer in 0 until 4) {
            starts[layer] = records.size
            for (from in 0 until if (layer == 0) 1 else 2) {
                for (symbol in 0..1) {
                    records.addAll(listOf(from.toLong(), symbol.toLong(), (from xor symbol).toLong(), 2L + from + symbol))
                }
            }
        }
        starts[4] = records.size
        val seq = intArrayOf(0, 1, 0, 2)
        val factor = Mdd(seq, intArrayOf(1, 2, 2, 2, 2), starts, records.toLongArray(), 0, intArrayOf(0), 4, 3)
        val problem = Problem(0, 4, Array(4) { IntDomain(0, 20) }, arrayOf<Factor>(factor))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        for (variable in 0..3) state.assignment.setInt(variable, 2)
        state.recompute()
        val words = (0 until 16).filter { it.countOneBits() % 2 == 0 }.map { mask ->
            val word = LongArray(4) { ((mask ushr it) and 1).toLong() }
            var parity = 0
            var weight = 0L
            for (symbol in word) {
                weight += 2 + parity + symbol
                parity = parity xor symbol.toInt()
            }
            word to weight
        }
        fun degree(): Long {
            val distance = words.minOf { (word, _) ->
                seq.indices.count { word[it] != state.assignment.intValue(seq[it]) }
            }
            val accepted = words.firstOrNull { (word, _) ->
                seq.indices.all { word[it] == state.assignment.intValue(seq[it]) }
            }
            return distance + if (accepted == null) 0L else kotlin.math.abs(state.assignment.intValue(3) - accepted.second)
        }

        val random = Random(29)
        repeat(40) {
            val move = Move.Compound(listOf(
                Move.IntSet(random.nextInt(3), random.nextLong(3)),
                Move.IntSet(3, random.nextLong(21)),
            ))
            val before = state.assignment.snapshot()
            val cost = state.cost
            val predicted = state.netDelta(move)
            assertEquals(before, state.assignment.snapshot())
            assertEquals(cost, state.cost)
            for (variable in 0..3) {
                val value = random.nextLong(3)
                val delta = state.netDelta(Move.IntSet(variable, value))
                val old = state.assignment.intValue(variable)
                state.assignment.setInt(variable, value)
                assertEquals(degree() - cost, delta)
                state.assignment.setInt(variable, old)
            }
            state.apply(move)
            assertEquals(degree(), state.cost)
            assertEquals(state.cost - cost, predicted)
        }
    }

    // Accepts exactly (1, 2) and (2, 1).
    private fun mddFactor(): Factor = Mdd(
        seq = intArrayOf(0, 1),
        numStatesPerLayer = intArrayOf(1, 2, 1),
        layerStarts = intArrayOf(0, 6, 12),
        transitions = longArrayOf(
            0, 1, 0, 0, 2, 1,
            0, 2, 0, 1, 1, 0,
        ),
        initial = 0,
        accepting = intArrayOf(0),
        recordStride = 3,
    )

    @Test
    fun `cost-MDD seed should set the cost var to an edge weight beyond Int range`() {
        val factor = Mdd(
            seq = intArrayOf(0),
            numStatesPerLayer = intArrayOf(1, 1),
            layerStarts = intArrayOf(0, 4),
            transitions = longArrayOf(0, 1, 0, 3_000_000_000L),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 4,
            cost = 1,
        )
        val problem = Problem(0, 2, arrayOf(IntDomain(1, 1), IntDomain(0, 5_000_000_000L)), arrayOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        assertTrue(state.factors[0].seedFeasible(state, 0))
        assertEquals(3_000_000_000L, state.assignment.intValue(1))
    }

    @Test
    fun `incremental degree and delta should match a full recompute over a stream of moves`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(mddFactor()),
        )
        val state = LocalSearchState(problem.bake(), Random(7))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.recompute()
        val rng = Random(99)
        repeat(400) { step ->
            val v = rng.nextInt(2)
            val nv = 1L + rng.nextInt(2)
            val before = state.factors[0].violationDegree(state, 0)
            val predicted = state.factors[0].deltaIfIntSet(state, 0, v, nv)
            state.apply(Move.IntSet(v, nv))
            val after = state.factors[0].violationDegree(state, 0)
            assertEquals(after - before, predicted, "step $step")
            val fresh = LocalSearchState(problem.bake(), Random(0))
            for (k in 0 until 2) fresh.assignment.setInt(k, state.assignment.intValue(k))
            fresh.recompute()
            assertEquals(
                fresh.factors[0].violationDegree(fresh, 0),
                state.factors[0].violationDegree(state, 0),
                "step $step",
            )
        }
    }

    @Test
    fun `DP-optimal repair moves should reach an accepted path`() {
        fun seeded(): LocalSearchState {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
                factors = arrayOf(mddFactor()),
            )
            val state = LocalSearchState(problem.bake(), Random(0))
            state.assignment.setInt(0, 1)
            state.assignment.setInt(1, 1)
            state.recompute()
            return state
        }
        val state = seeded()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeExtendedRepairMoves(state, 0, sink)
        assertTrue(sink.list.isNotEmpty())
        val check = seeded()
        for (m in sink.list) check.apply(m)
        assertFalse(check.factors[0].isViolated(check, 0))
    }

    @Test
    fun `a cost mdd word should be violated until its cost equals the path weight`() {
        val factor = Mdd(
            seq = intArrayOf(0),
            numStatesPerLayer = intArrayOf(1, 1),
            layerStarts = intArrayOf(0, 8),
            transitions = longArrayOf(0, 1, 0, 2, 0, 2, 0, 4),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 4,
            cost = 1,
        )
        val problem = Problem(0, 2, arrayOf(IntDomain(1, 2), IntDomain(0, 5)), arrayOf(factor))
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 4)
        state.recompute()
        val invariant = state.factors[0]

        val offBefore = invariant.isViolated(state, 0)
        val delta = invariant.deltaIfIntSet(state, 0, 1, 2L)

        assertTrue(offBefore)
        assertEquals(-invariant.violationDegree(state, 0), delta)
    }
}
