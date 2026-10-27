package com.eignex.klause.localsearch.movesource

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.Compound
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnusedValueEntriesTest {

    private fun seeded(domains: Array<IntDomain>, seed: Long = 0L): LocalSearchState {
        val problem = Problem(0, domains.size, domains, arrayOf<Factor>(AllDifferent(IntArray(domains.size) { it })))
        val state = LocalSearchState(problem.bake(), Random(seed))
        state.seedImplicitFeasible()
        state.recompute()
        return state
    }

    private fun proposals(state: LocalSearchState, sink: MoveSink = MoveSink()): MoveSink {
        sink.setOwners(state.seeding.ownerInt)
        MoveSourceCatalog.configured("unused-values").source.generate(state, sink)
        return sink
    }

    @Test
    fun `an owned scope with spare values should reach them directly and through an alternating path`() {
        val state = seeded(arrayOf(IntDomain(0, 0), IntDomain(1, 3).excludeValue(2), IntDomain(1, 2)))

        val moves = proposals(state).list

        assertEquals(setOf(IntSet(1, 3), Compound(listOf(IntSet(2, 1), IntSet(1, 3)))), moves.toSet())
        for (move in moves) assertEquals(0L, state.netDelta(move), "$move")
    }

    @Test
    fun `a closed permutation should propose nothing and draw no random value`() {
        val state = seeded(Array(3) { IntDomain(0, 2) }, seed = 3L)
        val control = seeded(Array(3) { IntDomain(0, 2) }, seed = 3L)

        val moves = proposals(state).list

        assertTrue(moves.isEmpty(), "$moves")
        assertEquals(control.rng.nextLong(), state.rng.nextLong())
    }

    @Test
    fun `pinned coordinates should keep their values`() {
        val state = seeded(Array(3) { IntDomain(0, 3) })
        state.assumptions = Assumptions(ints = mapOf(0 to 0L, 1 to 1L))

        val moves = proposals(state, MoveSink(state.assumptions)).list

        assertEquals(listOf(IntSet(2, 3)), moves)
    }

    @Test
    fun `an unowned scope should receive no owner moves`() {
        val problem = Problem(0, 3, Array(3) { IntDomain(0, 3) }, arrayOf<Factor>(AllDifferent(intArrayOf(0, 1, 2))))
        val state = LocalSearchState(problem.bake(), Random(0))
        for (i in 0 until 3) state.assignment.setInt(i, i.toLong())
        state.recompute()

        assertTrue(proposals(state).list.isEmpty())
    }
}
