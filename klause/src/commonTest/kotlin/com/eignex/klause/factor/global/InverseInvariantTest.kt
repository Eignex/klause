package com.eignex.klause.factor.global

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InverseInvariantTest {

    @Test
    fun `identity seed should reject conflicting values for overlapping channel variables`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(0, 1) },
            factors = arrayOf<Factor>(Inverse(intArrayOf(0, 1), intArrayOf(1, 0))),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        val before = (0..1).map { state.assignment.intValue(it) }

        val seeded = state.factors[0].seedFeasible(state, 0)

        assertFalse(seeded)
        assertEquals(before, (0..1).map { state.assignment.intValue(it) })
    }

    @Test
    fun `identity seed should support channel values beyond the largest int offset`() {
        val offset = Int.MAX_VALUE.toLong()
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(offset, offset + 1) },
            factors = arrayOf<Factor>(
                Inverse(intArrayOf(0, 1), intArrayOf(2, 3), Int.MAX_VALUE, Int.MAX_VALUE),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(0))

        assertTrue(state.factors[0].seedFeasible(state, 0))
        state.recompute()

        assertEquals(listOf(offset, offset + 1, offset, offset + 1), (0..3).map { state.assignment.intValue(it) })
        assertEquals(0L, state.cost)
    }

    private val f = intArrayOf(0, 1, 2)
    private val g = intArrayOf(3, 4, 5)

    private fun problem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 6,
        intDomains = Array(6) { IntDomain(0, 2) },
        factors = arrayOf<Factor>(Inverse(f, g, fOffset = 0, gOffset = 0)),
    )

    @Test
    fun `self-inverse structured moves should preserve the involution`() {
        val vars = intArrayOf(0, 1, 2, 3)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(Inverse(f = vars, g = vars)),
        )
        val starts = listOf(intArrayOf(0, 1, 2, 3), intArrayOf(1, 0, 3, 2))
        fun seeded(start: IntArray, seed: Long): LocalSearchState {
            val state = LocalSearchState(problem.bake(), Random(seed))
            for (i in 0 until 4) state.assignment.setInt(i, start[i].toLong())
            state.recompute()
            return state
        }
        var moves = 0
        for (start in starts) {
            for (seed in longArrayOf(1L, 2L, 3L, 7L, 11L)) {
                val state = seeded(start, seed)
                assertEquals(0L, state.cost)
                val sink = MoveSink()
                state.factors[0].proposeStructuredMoves(state, 0, sink)
                for (m in sink.list) {
                    moves++
                    val check = seeded(start, 0)
                    check.apply(m)
                    assertEquals(0L, check.cost, "$m")
                }
            }
        }
        assertTrue(moves > 0)
    }

    @Test
    fun `matching seed should find a feasible inverse when the identity is out of domain`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(1, 2),
                IntDomain(0, 2),
                IntDomain(0, 1),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
            ),
            factors = arrayOf<Factor>(Inverse(f, g, fOffset = 0, gOffset = 0)),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        assertTrue(state.factors[0].seedFeasible(state, 0))
        state.recompute()
        assertEquals(0L, state.cost)
    }

    @Test
    fun `delta should predict degree change on corrective assignment`() {
        val p = problem()
        val state = LocalSearchState(p.bake(), Random(0))
        for (i in 0..2) state.assignment.setInt(i, i.toLong())
        state.assignment.setInt(3, 2)
        state.assignment.setInt(4, 1)
        state.assignment.setInt(5, 2)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val before = state.factors[0].violationDegree(state, 0)
        val delta = state.factors[0].deltaIfIntSet(state, 0, 3, 0)
        state.apply(Move.IntSet(3, 0))
        val after = state.factors[0].violationDegree(state, 0)
        assertEquals(after - before, delta)
    }
}
