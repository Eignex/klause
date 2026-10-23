package com.eignex.klause.factor.global

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.undoTo
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllDifferentTuplesPropagatorTest {
    @Test
    fun `identical variable rows should conflict before coordinates are fixed`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(Long.MIN_VALUE, Long.MAX_VALUE) },
            arrayOf(AllDifferentTuples(listOf(intArrayOf(0, 1), intArrayOf(0, 1)))))
        val state = PropagationState(problem, Assumptions.None)

        assertFalse(problem.propagators[0].propagate(state, 0))
    }

    @Test
    fun `fixed tuple conflicts should explain every required coordinate`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 1) },
            arrayOf(AllDifferentTuples(listOf(intArrayOf(0, 0), intArrayOf(1, 2)))))

        PropagationReasonOracle.assertReasonsImply(problem, "equal tuples") { state ->
            state.tightenIntMax(0, 0) && state.tightenIntMax(1, 0) && state.tightenIntMax(2, 0)
        }
    }

    @Test
    fun `fixed tuple hashes should compare full signed word values after collisions`() {
        val problem = Problem(0, 4, arrayOf(IntDomain(0, 0), IntDomain(31, 31), IntDomain(1, 1), IntDomain(0, 0)),
            arrayOf(AllDifferentTuples(listOf(intArrayOf(0, 1), intArrayOf(2, 3)))))
        val state = PropagationState(problem, Assumptions.None)

        assertTrue(problem.propagators[0].propagate(state, 0))
    }

    @Test
    fun `retraction should clear a fixed tuple conflict`() {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 1) },
            arrayOf(AllDifferentTuples(listOf(intArrayOf(0), intArrayOf(1)))))
        val state = PropagationState(problem, Assumptions.None)
        val propagator = problem.propagators[0]
        state.undoLogging = true
        state.currentLevel = 1
        val mark = state.mark()
        assertTrue(state.tightenIntMax(0, 0) && state.tightenIntMax(1, 0))
        assertFalse(propagator.propagate(state, 0))

        state.undoTo(mark)

        assertTrue(state.tightenIntMax(0, 0) && state.tightenIntMin(1, 1))
        assertTrue(propagator.propagate(state, 0))
    }
}
