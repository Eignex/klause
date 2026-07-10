package com.eignex.klause.localsearch

import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.*
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class MoveCompoundMoveTest {

    /** Build a 3-var AllDifferent state where x0=x1=5, x2=0. */
    private fun setupAllDifferentConflict(): Pair<Problem, LocalSearchState> {
        val factor = AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 10)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 5)
        state.assignment.setInt(1, 5)
        state.assignment.setInt(2, 0)
        state.recompute()
        return problem to state
    }

    @Test
    fun `compound rejects nested compound and single part`() {
        assertFails { Move.Compound(emptyList()) }
        assertFails { Move.Compound(listOf(Move.BoolFlip(0))) }
        assertFails {
            Move.Compound(
                listOf(
                    Move.BoolFlip(0),
                    Move.Compound(listOf(Move.BoolFlip(1), Move.BoolFlip(2))),
                ),
            )
        }
    }

    @Test
    fun `compound apply commits all parts and updates cost`() {
        val (_, state) = setupAllDifferentConflict()
        assertEquals(1, state.cost, "x0=x1=5 should give a duplicate violation")
        state.apply(Move.Compound(listOf(Move.IntSet(0, 1), Move.IntSet(1, 2))))
        assertEquals(0, state.cost)
        assertEquals(1, state.assignment.intValue(0))
        assertEquals(2, state.assignment.intValue(1))
        assertEquals(0, state.assignment.intValue(2))
    }

    @Test
    fun `compound netDelta and breakScore restore state exactly`() {
        val (_, state) = setupAllDifferentConflict()
        val oldCost = state.cost
        val oldStep = state.step
        val oldX0 = state.assignment.intValue(0)
        val oldX1 = state.assignment.intValue(1)

        val resolveSwap = Move.Compound(listOf(Move.IntSet(0, 1), Move.IntSet(1, 2)))
        val delta = state.netDelta(resolveSwap)
        assertEquals(-1, delta, "resolving the duplicate should drop cost by 1")
        // No factor is newly broken: cost 1 → 0 fixes one, breaks none.
        assertEquals(0, state.breakScore(resolveSwap))

        assertEquals(oldCost, state.cost, "cost not restored")
        assertEquals(oldStep, state.step, "step not restored")
        assertEquals(oldX0, state.assignment.intValue(0), "x0 not restored")
        assertEquals(oldX1, state.assignment.intValue(1), "x1 not restored")
    }

    @Test
    fun `compound isTaboo if any part is taboo`() {
        val (_, state) = setupAllDifferentConflict()
        state.apply(Move.IntSet(0, 9))
        val compound = Move.Compound(listOf(Move.IntSet(0, 1), Move.IntSet(2, 7)))
        assertTrue(state.tabu.isTaboo(compound, tenure = 10), "x0 was just touched; compound must be tabu")

        val safe = Move.Compound(listOf(Move.IntSet(1, 4), Move.IntSet(2, 7)))
        assertTrue(!state.tabu.isTaboo(safe, tenure = 10), "x1/x2 untouched; compound must not be tabu")
    }

    @Test
    fun `move sink addCompound skips when any part touches frozen var`() {
        val sink = MoveSink(Assumptions(ints = mapOf(0 to 5)))
        sink.addCompound(listOf(Move.IntSet(0, 1), Move.IntSet(1, 2)))
        assertEquals(0, sink.list.size, "should be skipped when x0 is frozen")
        sink.addCompound(listOf(Move.IntSet(1, 4), Move.IntSet(2, 7)))
        assertEquals(1, sink.list.size, "should be added when no frozen part")
        assertTrue(sink.list[0] is Move.Compound)
    }
}
