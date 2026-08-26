package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Objective-hot-spot variable selection for [PairSwap]: when enabled, the first endpoint of every
 * int swap is an objective-relevant variable, so feasible-phase descent concentrates its coordinated
 * moves where they can move the objective instead of swapping objective-irrelevant pairs.
 */
class PairSwapHotSpotTest {

    /** Four int vars over one shared domain; the objective weights only vars 1 and 3. */
    private fun problem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 4,
        intDomains = Array(4) { IntDomain(0, 5) },
        factors = arrayOf<Factor>(Linear(intArrayOf(1, 1, 1, 1), intArrayOf(0, 1, 2, 3), LinearOp.LE, 99)),
    )

    private fun prepared(seed: Long): LocalSearchState {
        val state = LocalSearchState(problem(), Random(seed))
        state.shaping.objective = LinearObjective(intCoefficients = longArrayOf(0, 1, 0, 1))
        // Distinct values so int swaps are always legal.
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 4)
        state.assignment.setInt(2, 1)
        state.assignment.setInt(3, 3)
        state.recompute()
        return state
    }

    @Test
    fun `objective int vars are the nonzero-coefficient variables`() {
        assertEquals(listOf(1, 3), prepared(1).shaping.objectiveIntVars.toList())
    }

    @Test
    fun `objective hot-spot var sampling stays within the objective vars`() {
        val state = prepared(1)
        repeat(100) {
            assertTrue(state.shaping.objectiveHotSpotIntVar(state.rng) in intArrayOf(1, 3))
        }
    }

    @Test
    fun `objective hot-spot var is -1 when the objective exposes no int gradient`() {
        val state = LocalSearchState(problem(), Random(1))
        // No objective set.
        assertEquals(-1, state.shaping.objectiveHotSpotIntVar(state.rng))
    }

    @Test
    fun `hot-spot pair swaps always anchor their first endpoint on an objective var`() {
        val state = prepared(7)
        val sink = MoveSink()
        var swaps = 0
        repeat(200) {
            sink.clear()
            PairSwap.hotSpot(cap = 8).generate(state, sink)
            for (m in sink.list) {
                m as Move.Compound
                val first = m.parts[0]
                if (first is Move.IntSet) {
                    swaps++
                    assertTrue(
                        first.varId == 1 || first.varId == 3,
                        "first endpoint ${first.varId} must be an objective var",
                    )
                }
            }
        }
        assertTrue(swaps > 0, "the fixture must produce int swaps")
    }
}
