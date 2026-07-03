package com.eignex.klause.factor.global

import com.eignex.klause.factor.global.Inverse
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InverseInvariantTest {

    // Vars 0..2 = f[0..2], vars 3..5 = g[0..2], both over domain [0,2]
    private val f = intArrayOf(0, 1, 2)
    private val g = intArrayOf(3, 4, 5)

    private fun problem(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 6,
        intDomains = Array(6) { IntDomain(0, 2) },
        factors = arrayOf<Factor>(Inverse(f, g, fOffset = 0, gOffset = 0)),
    )

    @Test
    fun `self-inverse structured moves preserve the involution`() {
        // XCSP3 channel over one list: f and g are the same variables, so the constraint is f(f(x))=x.
        val vars = intArrayOf(0, 1, 2, 3)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(Inverse(f = vars, g = vars)),
        )
        // Two valid involutions to start from: all fixed points, and two transpositions.
        val starts = listOf(intArrayOf(0, 1, 2, 3), intArrayOf(1, 0, 3, 2))
        fun seeded(start: IntArray, seed: Long): LocalSearchState {
            val state = LocalSearchState(problem, Random(seed))
            for (i in 0 until 4) state.assignment.setInt(i, start[i])
            state.recompute()
            return state
        }
        var moves = 0
        for (start in starts) {
            for (seed in longArrayOf(1L, 2L, 3L, 7L, 11L)) {
                val state = seeded(start, seed)
                assertTrue(state.cost == 0L, "the starting involution must be feasible")
                val sink = MoveSink()
                state.factors[0].proposeStructuredMoves(state, 0, sink)
                for (m in sink.list) {
                    moves++
                    val check = seeded(start, 0)
                    check.apply(m)
                    assertTrue(check.cost == 0L, "involution move $m broke the self-inverse")
                }
            }
        }
        assertTrue(moves > 0, "self-inverse must propose involution-preserving moves")
    }

    @Test
    fun `matching seed finds a feasible inverse when the identity is out of domain`() {
        // f[0] cannot be 0 and f[2] cannot be 2, so the identity permutation is infeasible; a
        // matching seed must still find a feasible inverse pair.
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
        val state = LocalSearchState(problem, Random(0))
        assertTrue(state.factors[0].seedFeasible(state, 0), "matching seed must find a feasible inverse")
        state.recompute()
        assertEquals(0L, state.cost, "the seeded assignment must satisfy inverse")
    }

    @Test
    fun `not violated when f and g are a true inverse pair`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // f = [1, 2, 0], g = [2, 0, 1]: f[i]=j ↔ g[j]=i
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 0)
        state.assignment.setInt(3, 2)
        state.assignment.setInt(4, 0)
        state.assignment.setInt(5, 1)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
        assertEquals(0, state.factors[0].violationDegree(state, 0))
    }

    @Test
    fun `violated when back-link is wrong`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // f = [0, 1, 2], g = [1, 1, 2]: g[f[0]]=g[0]=1, but should be 0 → violated
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 1)
        state.assignment.setInt(4, 1)
        state.assignment.setInt(5, 2)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `not violated for identity permutation`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // f = [0,1,2], g = [0,1,2]: identity is its own inverse
        for (i in 0..2) state.assignment.setInt(i, i)
        for (i in 0..2) state.assignment.setInt(3 + i, i)
        state.recompute()
        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `delta predicts degree change on corrective assignment`() {
        val p = problem()
        val state = LocalSearchState(p, Random(0))
        // Start with identity, break g[0] to 2 (was 0)
        for (i in 0..2) state.assignment.setInt(i, i)
        state.assignment.setInt(3, 2)
        state.assignment.setInt(4, 1)
        state.assignment.setInt(5, 2)
        state.recompute()
        assertTrue(state.factors[0].isViolated(state, 0))
        val before = state.factors[0].violationDegree(state, 0)
        // Fix g[0] back to 0
        val delta = state.factors[0].deltaIfIntSet(state, 0, 3, 0)
        state.apply(Move.IntSet(3, 0))
        val after = state.factors[0].violationDegree(state, 0)
        assertEquals(after - before, delta)
    }
}
