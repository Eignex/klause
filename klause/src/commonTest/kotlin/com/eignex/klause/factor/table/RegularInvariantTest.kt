package com.eignex.klause.factor.table

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegularInvariantTest {

    // Accepts words ending in 2.
    private val transitions = longArrayOf(1, 2, 1, 2)

    private fun endsWith2Factor(n: Int): Factor = Regular(
        seq = IntArray(n) { it },
        numStates = 2,
        alphabetSize = 2,
        transitions = transitions,
        q0 = 1,
        accepting = intArrayOf(2),
    )

    @Test
    fun `incremental degree and delta should match a full recompute over a stream of moves`() {
        val n = 5
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(endsWith2Factor(n)),
        )
        val state = LocalSearchState(problem.bake(), Random(7))
        for (i in 0 until n) state.assignment.setInt(i, 1)
        state.recompute()
        val rng = Random(99)
        repeat(500) { step ->
            val v = rng.nextInt(n)
            val nv = 1L + rng.nextInt(2)
            val before = state.factors[0].violationDegree(state, 0)
            val predicted = state.factors[0].deltaIfIntSet(state, 0, v, nv)
            state.apply(Move.IntSet(v, nv))
            val after = state.factors[0].violationDegree(state, 0)
            assertEquals(after - before, predicted, "step $step")
            val fresh = LocalSearchState(problem.bake(), Random(0))
            for (k in 0 until n) fresh.assignment.setInt(k, state.assignment.intValue(k))
            fresh.recompute()
            assertEquals(
                fresh.factors[0].violationDegree(fresh, 0),
                state.factors[0].violationDegree(state, 0),
                "step $step",
            )
        }
    }

    @Test
    fun `DP-optimal repair moves should reach an accepting run`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(endsWith2Factor(3)),
        )
        fun seeded(): LocalSearchState {
            val state = LocalSearchState(problem.bake(), Random(0))
            state.assignment.setInt(0, 1)
            state.assignment.setInt(1, 2)
            state.assignment.setInt(2, 1)
            state.recompute()
            return state
        }
        val state = seeded()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeExtendedRepairMoves(state, 0, sink)
        assertTrue(sink.list.isNotEmpty())
        val check = seeded()
        for (move in sink.list) check.apply(move)
        assertFalse(check.factors[0].isViolated(check, 0))
    }

    @Test
    fun `reinitialized regular scores should stay private to each state`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(1, 2) }, arrayOf(endsWith2Factor(3)))
        val model = LocalSearchModel.open(problem)
        val projection = LocalSearchProblem(problem)
        val left = LocalSearchState(model, Random(0), projection = projection)
        val right = LocalSearchState(model, Random(1), projection = projection)
        for (state in listOf(left, right)) {
            for (variable in 0 until 3) state.assignment.setInt(variable, 1L)
            state.recompute()
        }
        left.assignment.setInt(2, 2L)

        left.recompute()
        right.recompute()

        assertEquals(0, left.factors[0].violationDegree(left, 0))
        assertEquals(1, right.factors[0].violationDegree(right, 0))
        assertEquals(1, left.factors[0].deltaIfIntSet(left, 0, 2, 1L))
        assertEquals(-1, right.factors[0].deltaIfIntSet(right, 0, 2, 2L))
    }

    @Test
    fun `compound probes should restore regular scores after rebuilding their layers`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(1, 2) }, arrayOf(endsWith2Factor(3)))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        for (variable in 0 until 3) state.assignment.setInt(variable, 1L)
        state.assignment.setInt(2, 2L)
        state.recompute()
        val move = Move.Compound(listOf(Move.IntSet(0, 2L), Move.IntSet(2, 1L)))

        val delta = state.netDelta(move)

        assertEquals(1L, delta)
        assertContentEquals(longArrayOf(1, 1, 2), state.assignment.snapshot().ints)
        assertEquals(0L, state.cost)
        assertEquals(1, state.factors[0].deltaIfIntSet(state, 0, 2, 1L))
    }
}
