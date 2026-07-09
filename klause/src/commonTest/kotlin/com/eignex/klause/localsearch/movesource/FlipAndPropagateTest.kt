package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behaviour tests for [FlipAndPropagate]: a seed flip is emitted together with the literals it forces
 * through [ImplicitSeeding.implicationGraph], the bundle is one atomic [Move.Compound] that applies
 * and reverts cleanly, no-op forced parts are dropped, and the implication-following depth is bounded.
 * Implication directions are read off the graph the problem actually builds, never assumed.
 */
class FlipAndPropagateTest {

    /** `x0 -> x1` via clause `(¬x0 ∨ x1)`, with a unit clause `(x0)` so x0 sits in a violated factor
     *  at the all-false start (the seed). */
    private fun impliesProblem(): Problem = Problem(
        numBoolVars = 2,
        numIntVars = 0,
        intDomains = arrayOf(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, true))),
        ),
    )

    /** `x0 -> x1` and `x0 -> x2` (a fork), plus the unit `(x0)` seed factor. Forking keeps the seed
     *  the sole violated factor even after one forced variable is pre-satisfied, so the no-op-drop
     *  test stays deterministic. */
    private fun forkProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 0,
        intDomains = arrayOf(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(0, true))),
        ),
    )

    /** `x0 -> x1 -> x2` via two binary clauses, plus the unit `(x0)` seed factor. */
    private fun chainProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 0,
        intDomains = arrayOf(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(0, true))),
        ),
    )

    /** The parts of the single compound [FlipAndPropagate] emits for [state] at the given [maxDepth]. */
    private fun emittedParts(state: LocalSearchState, maxDepth: Int = 16): Set<Move> {
        val sink = MoveSink(state.assumptions)
        sink.setInvariants(state.invariants)
        FlipAndPropagate(cap = 4, maxDepth = maxDepth).generate(state, sink)
        val compounds = sink.list.filterIsInstance<Move.Compound>()
        assertEquals(1, compounds.size, "exactly one implication-flip compound expected")
        return compounds.single().parts.toSet()
    }

    @Test
    fun `flipping a seed also flips the literal it forces`() {
        val state = freshState(impliesProblem(), seed = 1L)
        // Read the implication direction off the graph rather than assuming it.
        val forced = state.seeding.implicationGraph[Lit.make(0, true)].toList()
        assertTrue(Lit.make(1, true) in forced, "pinning x0=true must force x1=true")

        val parts = emittedParts(state)
        assertTrue(Move.BoolFlip(0) in parts, "the seed flip is present")
        assertTrue(Move.BoolFlip(1) in parts, "the forced literal is bundled into the same compound")
    }

    @Test
    fun `a transitive chain bundles every forced flip`() {
        val state = freshState(chainProblem(), seed = 1L)
        assertEquals(
            setOf(Move.BoolFlip(0), Move.BoolFlip(1), Move.BoolFlip(2)),
            emittedParts(state),
            "x0 to x1 to x2 must bundle all three flips",
        )
    }

    @Test
    fun `applying then reverting the compound restores the prior state`() {
        val state = freshState(chainProblem(), seed = 1L)
        val before = state.assignment.snapshot()
        val beforeCost = state.cost
        // Bool-flip parts self-invert, so re-applying the same compound reverts it exactly.
        val compound = Move.Compound(emittedParts(state).toList())

        state.apply(compound)
        state.apply(compound)

        assertEquals(beforeCost, state.cost, "cost restored after apply then revert")
        for (v in 0 until state.problem.numBoolVars) {
            assertEquals(before.bools[v], state.assignment.boolValue(v), "bool x$v restored")
        }
    }

    @Test
    fun `a variable already satisfying a forced value adds no redundant part`() {
        val state = freshState(forkProblem(), seed = 1L)
        // Pre-set x1 to the value x0=true forces (true). The forced literal x1=true is then a no-op
        // and must not appear as a part; the other forced flip x2 still flips.
        state.assignment.setBool(1, true)
        state.recompute()
        val parts = emittedParts(state)
        assertTrue(Move.BoolFlip(1) !in parts, "an already-satisfied forced literal contributes no part")
        assertTrue(
            Move.BoolFlip(0) in parts && Move.BoolFlip(2) in parts,
            "the seed and the unsatisfied forced flip remain",
        )
    }

    @Test
    fun `the implication-following depth is bounded`() {
        val state = freshState(chainProblem(), seed = 1L)
        // A depth-1 cap still captures the seed's transitively-closed forced set (the graph stores the
        // closure per literal) and the walk terminates rather than chasing a longer ladder.
        assertEquals(
            setOf(Move.BoolFlip(0), Move.BoolFlip(1), Move.BoolFlip(2)),
            emittedParts(state, maxDepth = 1),
            "depth 1 captures the seed's closure and stops",
        )
    }
}
