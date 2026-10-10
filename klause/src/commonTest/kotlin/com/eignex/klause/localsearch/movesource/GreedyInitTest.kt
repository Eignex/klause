package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behaviour test for the [GreedyInit] restart initializer. It is not a candidate generator (it
 * mutates in place), so the equivalence harness does not apply; instead this pins the two properties
 * the engine relies on: a forward pass reduces violation, and it is deterministic for a fixed seed.
 */
class GreedyInitTest {

    @Test
    fun `a greedy pass preserves a comparison output defined by a frozen input`() {
        val problem = Problem(
            3, 1, arrayOf(IntDomain(0, 1)),
            arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            ),
        )
        val state = freshState(problem, 7L)
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars))
            .network(problem.numIntVars, problem.numBoolVars)
        state.assumptions = Assumptions(bools = mapOf(1 to false, 2 to false), ints = mapOf(0 to 1L))
        state.assignment.setInt(0, 1L)
        state.recompute()

        GreedyInit().run(state)

        assertFalse(state.assignment.boolValue(0))
    }

    @Test
    fun `a greedy pass preserves a product output defined by frozen inputs`() {
        val problem = Problem(
            3, 3, arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 4)),
            arrayOf<Factor>(Product(0, 1, 2)) + Array<Factor>(3) { bool ->
                ReifiedLinear(bool, intArrayOf(1), intArrayOf(2), LinearOp.EQ, 2)
            },
        )
        val state = freshState(problem, 7L)
        state.invariants = assertNotNull(DefinitionalSweep.infer(problem.factors, problem.numIntVars))
            .network(problem.numIntVars, problem.numBoolVars)
        state.assumptions = Assumptions(
            bools = mapOf(0 to true, 1 to true, 2 to true), ints = mapOf(0 to 0L, 1 to 0L),
        )
        for (bool in 0..2) state.assignment.setBool(bool, true)
        state.recompute()

        GreedyInit().run(state)

        assertEquals(0L, state.assignment.intValue(2))
    }

    @Test
    fun `a greedy pass preserves an implicitly seeded permutation`() {
        val problem = Problem(
            1, 3, Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(
                AllDifferent(intArrayOf(0, 1, 2), 0, 3),
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
            ),
        )
        val state = freshState(problem, 7L)
        state.seedImplicitFeasible()
        state.assumptions = Assumptions(bools = mapOf(0 to true))
        state.assignment.setBool(0, true)
        state.recompute()
        val seeded = state.assignment.snapshot()

        GreedyInit().run(state)

        assertEquals(seeded, state.assignment.snapshot())
    }

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
