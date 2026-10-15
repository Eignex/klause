package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliqueSwapTest {

    private fun amoProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(amoCardinality(0, 1, 2)),
    )

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
    fun `one member on should yield swaps to the other members`() {
        val state = freshState(amoProblem(), 7L)
        state.assignment.flipBool(0) // var 0 on, the clique's single satisfied member
        state.recompute()
        val moves = swaps(state, cap = 16)
        assertTrue(moves.isNotEmpty(), "an on clique member must yield swap candidates (got $moves)")
        val partners = moves.map { swapPartner(it, offVar = 0) }.toSet()
        assertEquals(setOf(1, 2), partners, "swaps must turn var 0 off and another member on (got $moves)")
    }

    @Test
    fun `an over-full clique should yield a repair turning both members off`() {
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
    fun `a singleton clique should yield no candidates`() {
        val state = freshState(singletonProblem(), 7L)
        state.assignment.flipBool(0)
        state.recompute()
        assertTrue(swaps(state, cap = 16).isEmpty())
    }

    private fun swapPartner(move: Move, offVar: Int): Int = flippedVars(move).single { it != offVar }

    private fun flippedVars(move: Move): Set<Int> =
        (move as Move.Compound).parts.map { (it as Move.BoolFlip).varId }.toSet()
}
