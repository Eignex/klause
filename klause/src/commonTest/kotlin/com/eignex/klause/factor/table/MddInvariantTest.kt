package com.eignex.klause.factor.table

import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MddInvariantTest {

    /** MDD accepting exactly (1,2) and (2,1) over a 2-symbol alphabet. */
    private fun mddFactor(): Factor = Mdd(
        seq = intArrayOf(0, 1),
        numStatesPerLayer = intArrayOf(1, 2, 1),
        layerStarts = intArrayOf(0, 6, 12),
        transitions = longArrayOf(
            0, 1, 0, 0, 2, 1, // layer 0: s0 --1--> s0; s0 --2--> s1
            0, 2, 0, 1, 1, 0, // layer 1: s0 --2--> term; s1 --1--> term
        ),
        initial = 0,
        accepting = intArrayOf(0),
        recordStride = 3,
    )

    @Test
    fun `satisfied when assignment follows an accepted path`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(mddFactor()),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1) // (1,2) is accepted
        state.assignment.setInt(1, 2)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `cost-MDD seed sets the cost var to an edge weight beyond Int range`() {
        // A single accepted path (symbol 1) whose edge weight is 3e9. seedFeasible sums the path weight
        // in Long and assigns it to the cost variable, so a weight past 2^31 lands intact.
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
        val state = LocalSearchState(problem, Random(0))
        assertTrue(state.factors[0].seedFeasible(state, 0), "the single accepted path must seed")
        assertEquals(3_000_000_000L, state.assignment.intValue(1))
    }

    @Test
    fun `incremental degree and delta match a full recompute over a stream of moves`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(mddFactor()),
        )
        val state = LocalSearchState(problem, Random(7))
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
            assertEquals(after - before, predicted, "step $step: incremental delta mismatch")
            val fresh = LocalSearchState(problem, Random(0))
            for (k in 0 until 2) fresh.assignment.setInt(k, state.assignment.intValue(k))
            fresh.recompute()
            assertEquals(
                fresh.factors[0].violationDegree(fresh, 0),
                state.factors[0].violationDegree(state, 0),
                "step $step: maintained degree drifted from a full recompute",
            )
        }
    }

    @Test
    fun `DP-optimal repair moves reach an accepted path`() {
        fun seeded(): LocalSearchState {
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2,
                intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
                factors = arrayOf(mddFactor()),
            )
            val state = LocalSearchState(problem, Random(0))
            state.assignment.setInt(0, 1) // (1,1) is rejected
            state.assignment.setInt(1, 1)
            state.recompute()
            return state
        }
        val state = seeded()
        assertTrue(state.factors[0].isViolated(state, 0))
        val sink = MoveSink()
        state.factors[0].proposeExtendedRepairMoves(state, 0, sink)
        assertTrue(sink.list.isNotEmpty(), "extended repair must propose moves")
        val check = seeded()
        for (m in sink.list) check.apply(m)
        assertFalse(check.factors[0].isViolated(check, 0), "applying the DP-optimal repair must reach an accepted path")
    }

    @Test
    fun `violated when assignment follows a rejected path`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(mddFactor()),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1) // (1,1) is rejected
        state.assignment.setInt(1, 1)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        assertTrue(state.factors[0].violationDegree(state, 0) > 0)
    }

    @Test
    fun `delta is negative when move leads to accepted path`() {
        // Violated: (1,1). Setting seq[1]=2 → (1,2) accepted → delta < 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(mddFactor()),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val delta = state.factors[0].deltaIfIntSet(state, 0, intVar = 1, newValue = 2)
        assertTrue(delta < 0, "move to (1,2) should reduce violation; delta=$delta")
    }

    @Test
    fun `ls solver finds only accepted words`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(mddFactor()),
        )
        val solver = LocalSearchSolver(problem.bake(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 100))
        val samples = solver.enumerate(LocalSearchParams(maxFlips = 2_000, randomSeed = 0)).take(10).toList()
        assertTrue(samples.isNotEmpty())
        for (s in samples) {
            val a = s.ints[0]
            val b = s.ints[1]
            assertTrue(
                (a == 1L && b == 2L) || (a == 2L && b == 1L),
                "rejected word ($a,$b)",
            )
        }
    }
}
