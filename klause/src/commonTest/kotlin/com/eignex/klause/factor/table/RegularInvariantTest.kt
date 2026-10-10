package com.eignex.klause.factor.table

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RegularInvariantTest {

    // DFA: alphabet {1,2}, states {1,2}, q0=1, F={2}.
    // δ(1,1)=1, δ(1,2)=2, δ(2,1)=1, δ(2,2)=2. Accepts strings ending in 2.
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
    fun `incremental degree and delta match a full recompute over a stream of moves`() {
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
            assertEquals(after - before, predicted, "step $step: incremental delta mismatch")
            val fresh = LocalSearchState(problem.bake(), Random(0))
            for (k in 0 until n) fresh.assignment.setInt(k, state.assignment.intValue(k))
            fresh.recompute()
            assertEquals(
                fresh.factors[0].violationDegree(fresh, 0),
                state.factors[0].violationDegree(state, 0),
                "step $step: maintained degree drifted from a full recompute",
            )
        }
    }

    @Test
    fun `DP-optimal repair moves reach an accepting run`() {
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
            state.assignment.setInt(2, 1) // ends in 1 → violated
            state.recompute()
            return state
        }
        val state = seeded()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeExtendedRepairMoves(state, 0, sink)
        assertTrue(sink.list.isNotEmpty(), "repair must propose moves")
        val check = seeded()
        for (move in sink.list) check.apply(move)
        assertFalse(check.factors[0].isViolated(check, 0), "applying the DP-optimal repair must reach an accepting run")
    }

}
