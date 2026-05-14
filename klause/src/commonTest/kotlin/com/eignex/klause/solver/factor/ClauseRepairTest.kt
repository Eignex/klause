package com.eignex.klause.solver.factor
import com.eignex.klause.solver.localsearch.LocalSearchFactor

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.Move
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClauseRepairTest {

    @Test
    fun `violated clause proposes every var once`() {

        val a = 0; val b = 1; val c = 2
        val factor = Clause(intArrayOf(Lit.make(a, true), Lit.make(b, false), Lit.make(c, true)))
        val problem = Problem(3, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(a, false)
        state.assignment.setBool(b, true)
        state.assignment.setBool(c, false)
        state.recompute()
        assertTrue(factor.isViolated(state, 0))

        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        val proposed = sink.list.filterIsInstance<Move.BoolFlip>().map { it.varId }.toSet()
        assertEquals(setOf(a, b, c), proposed)
    }

    @Test
    fun `satisfied clause proposes nothing`() {
        val a = 0; val b = 1
        val factor = Clause(intArrayOf(Lit.make(a, true), Lit.make(b, true)))
        val problem = Problem(2, 0, emptyArray(), listOf(factor))
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setBool(a, true)
        state.assignment.setBool(b, false)
        state.recompute()
        assertTrue(!factor.isViolated(state, 0))
        val sink = MoveSink()
        factor.proposeRepairMoves(state, 0, sink)
        assertTrue(sink.list.isEmpty())
    }
}
