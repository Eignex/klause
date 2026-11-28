package com.eignex.klause.factor.circuit

import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.bake
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircuitInvariantTest {

    private fun fourNodeProblem(): Problem {
        val factor = Circuit(succ = intArrayOf(0, 1, 2, 3))
        return Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `applying a move that breaks the cycle should raise the cost`() {
        val problem = fourNodeProblem()
        val state = LocalSearchState(problem.bake(), Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 3)
        state.assignment.setInt(3, 0)
        state.recompute()
        assertEquals(0, state.cost)
        state.apply(IntSet(0, 2))
        assertTrue(state.cost > 0)
    }

    private fun nNodeCircuit(n: Int): Problem = Problem(
        numBoolVars = 0,
        numIntVars = n,
        intDomains = Array(n) { IntDomain(0, (n - 1).toLong()) },
        factors = arrayOf<Factor>(Circuit(succ = IntArray(n) { it })),
    )

    private fun seedTour(state: LocalSearchState, n: Int) {
        for (i in 0 until n) state.assignment.setInt(i, ((i + 1) % n).toLong())
        state.recompute()
    }

    @Test
    fun `every structured circuit move should preserve the Hamiltonian tour`() {
        val n = 8
        val circuit = nNodeCircuit(n).bake()
        val state = LocalSearchState(circuit, Random(1L))
        seedTour(state, n)
        assertEquals(0, state.cost)
        val sink = MoveSink()
        state.factors[0].proposeExtendedStructuredMoves(state, 0, sink)
        for (move in sink.list) {
            val check = LocalSearchState(circuit, Random(0))
            seedTour(check, n)
            check.apply(move)
            assertEquals(0, check.cost, "$move")
        }
    }

    @Test
    fun `2-opt reversals should reach compounds a 3-edge swap cannot`() {
        val n = 8
        val circuit = nNodeCircuit(n).bake()
        val state = LocalSearchState(circuit, Random(0L))
        seedTour(state, n)
        val sink = MoveSink()
        state.factors[0].proposeExtendedStructuredMoves(state, 0, sink)
        val maxParts = sink.list.maxOf { if (it is Move.Compound) it.parts.size else 0 }
        assertTrue(maxParts > 3, "max parts $maxParts")
    }

    private fun subcircuitWithExcluded(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 6,
        intDomains = Array(6) { IntDomain(0, 5) },
        factors = arrayOf<Factor>(Circuit(succ = intArrayOf(0, 1, 2, 3, 4, 5), subcircuit = true)),
    )

    private fun seededSubcircuit(seed: Long): LocalSearchState {
        val state = LocalSearchState(subcircuitWithExcluded().bake(), Random(seed))
        for (i in 0 until 5) state.assignment.setInt(i, ((i + 1) % 5).toLong())
        state.assignment.setInt(5, 5)
        state.recompute()
        return state
    }

    @Test
    fun `every structured subcircuit move should preserve the active sub-tour`() {
        val state = seededSubcircuit(1L)
        assertEquals(0, state.cost)
        val sink = MoveSink()
        state.factors[0].proposeExtendedStructuredMoves(state, 0, sink)
        for (move in sink.list) {
            val check = seededSubcircuit(0)
            check.apply(move)
            assertEquals(0, check.cost, "$move")
        }
    }
}
