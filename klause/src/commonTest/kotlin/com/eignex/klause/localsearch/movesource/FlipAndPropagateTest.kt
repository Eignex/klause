package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlipAndPropagateTest {

    private fun forkProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 0,
        intDomains = arrayOf(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(0, false), Lit.make(2, true))),
        ),
    )

    private fun chainProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 0,
        intDomains = arrayOf(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
        ),
    )

    private fun emittedParts(state: LocalSearchState, maxDepth: Int = 16): Set<Move> =
        assertNotNull(state.buildImplicationFlip(state.seeding.implicationGraph, 0, maxDepth)).toSet()

    @Test
    fun `a transitive chain should bundle every forced flip`() {
        val state = freshState(chainProblem(), seed = 1L)
        assertEquals(
            setOf(Move.BoolFlip(0), Move.BoolFlip(1), Move.BoolFlip(2)),
            emittedParts(state),
            "x0 to x1 to x2 must bundle all three flips",
        )
    }

    @Test
    fun `a variable already satisfying a forced value should add no redundant part`() {
        val state = freshState(forkProblem(), seed = 1L)
        state.assignment.setBool(1, true)
        state.recompute()
        val parts = emittedParts(state)
        assertTrue(Move.BoolFlip(1) !in parts)
        assertTrue(
            Move.BoolFlip(0) in parts && Move.BoolFlip(2) in parts,
            "the seed and the unsatisfied forced flip remain",
        )
    }

    @Test
    fun `a root fixed seed should produce no implication flip compound`() {
        val source = chainProblem()
        val problem = source.withFactors(source.factors + Clause(intArrayOf(Lit.make(0, true))))
        val state = freshState(problem, seed = 1L)
        val sink = MoveSink(state.assumptions)
        sink.setInvariants(state.invariants)

        FlipAndPropagate(cap = 4, maxDepth = 16).generate(state, sink)

        assertTrue(sink.list.filterIsInstance<Move.Compound>().isEmpty())
    }
}
