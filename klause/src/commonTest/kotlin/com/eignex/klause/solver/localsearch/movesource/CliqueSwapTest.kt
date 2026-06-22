package com.eignex.klause.solver.localsearch.movesource

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.bool.Cardinality
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behaviour tests for the at-most-one clique-swap source [CliqueSwap]: a clique with one member on
 * yields swaps that relocate the on member, an over-full clique yields a repair, and every emitted
 * compound applies and reverts cleanly.
 */
class CliqueSwapTest {

    /** A single at-most-one clique over three positive literals on bool vars 0,1,2. */
    private fun amoProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(amoCardinality(0, 1, 2)),
    )

    /** A degenerate clique of a single literal — no swap partner exists. */
    private fun singletonProblem(): Problem = Problem(
        numBoolVars = 1,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(amoCardinality(0)),
    )

    private fun amoCardinality(vararg vars: Int): Cardinality =
        Cardinality(IntArray(vars.size) { Lit.make(vars[it], positive = true) }, min = 0, max = 1)

    private fun swaps(state: LocalSearchState, cap: Int): List<Move> {
        val sink = MoveSink(state.assumptions)
        sink.setInvariants(state.invariants)
        sink.clear()
        CliqueSwap(cap).generate(state, sink)
        return sink.list
    }

    @Test
    fun `one member on yields swaps to the other members`() {
        val state = freshState(amoProblem(), 7L)
        state.assignment.flipBool(0) // var 0 on, the clique's single satisfied member
        state.recompute()
        val moves = swaps(state, cap = 16)
        assertTrue(moves.isNotEmpty(), "an on clique member must yield swap candidates (got $moves)")
        val partners = moves.map { swapPartner(it, offVar = 0) }.toSet()
        assertEquals(setOf(1, 2), partners, "swaps must turn var 0 off and another member on (got $moves)")
    }

    @Test
    fun `an over-full clique yields a repair turning both members off`() {
        val state = freshState(amoProblem(), 7L)
        state.assignment.flipBool(0)
        state.assignment.flipBool(1) // two members on: the clique is violated
        state.recompute()
        assertTrue(state.cost > 0L, "two members on violates the at-most-one clique")
        val repair = swaps(state, cap = 16).map { flippedVars(it) }
        assertTrue(
            repair.contains(setOf(0, 1)),
            "the repair must turn both on members off (got $repair)",
        )
    }

    @Test
    fun `applying then reverting a generated swap restores the prior state`() {
        val state = freshState(amoProblem(), 7L)
        state.assignment.flipBool(0)
        state.recompute()
        val before = (0 until state.problem.numBoolVars).map { state.assignment.boolValue(it) }
        val cost = state.cost
        val move = swaps(state, cap = 16).first()
        state.apply(move)
        for (p in (move as Move.Compound).parts.reversed()) state.apply(p) // BoolFlip self-inverts
        assertEquals(
            before,
            (0 until state.problem.numBoolVars).map { state.assignment.boolValue(it) },
            "apply then revert must restore the exact assignment",
        )
        assertEquals(cost, state.cost, "apply then revert must restore the exact cost")
    }

    @Test
    fun `a singleton clique yields no candidates`() {
        val state = freshState(singletonProblem(), 7L)
        state.assignment.flipBool(0)
        state.recompute()
        assertTrue(swaps(state, cap = 16).isEmpty(), "a clique with no swap partner yields nothing")
    }

    /** The on-other variable of a two-flip clique swap whose other flipped variable is [offVar]. */
    private fun swapPartner(move: Move, offVar: Int): Int = flippedVars(move).single { it != offVar }

    /** The set of variables a two-flip clique-swap compound touches. */
    private fun flippedVars(move: Move): Set<Int> =
        (move as Move.Compound).parts.map { (it as Move.BoolFlip).varId }.toSet()
}
