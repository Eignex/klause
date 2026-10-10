package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.Move
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behaviour test for the [GreedyInit] restart initializer. It is not a candidate generator (it
 * mutates in place), so the equivalence harness does not apply; instead this pins the two properties
 * the engine relies on: a forward pass reduces violation, and it is deterministic for a fixed seed.
 */
class GreedyInitTest {

    /** A two-variable sum `x0 + x1 = 6` over 0..5 each: from the all-zero start (degree 6),
     *  coordinate descent improves on the first variable touched (best partial value) and zeroes
     *  it on the second, so a single forward pass strictly reduces violation. */
    private fun problem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
        factors = arrayOf<Factor>(Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 6)),
    )

    @Test
    fun `a greedy pass reduces violation`() {
        val state = freshState(problem(), 7L)
        val before = state.cost
        assertTrue(before > 0L, "all-zero start must violate the sum constraint")
        GreedyInit().run(state)
        assertTrue(state.cost < before, "coordinate-greedy must reduce violation (was $before, got ${state.cost})")
    }

    @Test
    fun `the pass is deterministic for a fixed seed`() {
        val a = freshState(problem(), 42L).also { GreedyInit().run(it) }
        val b = freshState(problem(), 42L).also { GreedyInit().run(it) }
        assertEquals(a.assignment.intValue(0), b.assignment.intValue(0))
        assertEquals(a.assignment.intValue(1), b.assignment.intValue(1))
    }

    @Test
    fun `a pass told to stop leaves the assignment as it was`() {
        val state = freshState(problem(), 7L)

        GreedyInit().run(state) { true }

        assertEquals(listOf(0L, 0L), listOf(state.assignment.intValue(0), state.assignment.intValue(1)))
    }

    @Test
    fun `cancellation after a coordinate keeps its repair and skips the next coordinate`() {
        val state = freshState(problem(), 7L)
        var checks = 0

        GreedyInit().run(state) { ++checks > 1 }

        assertEquals(1L, state.cost)
    }

    @Test
    fun `a cancelled pass clears repair activity before the search resumes`() {
        val state = freshState(problem(), 7L)
        state.apply(Move.IntSet(0, 1L))

        GreedyInit().run(state) { true }

        assertEquals(0L, state.step)
    }
}
