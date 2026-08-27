package com.eignex.klause.factor.circuit

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.solver.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubcircuitInvariantTest {

    private fun problem(n: Int): Problem {
        val factor = Circuit(succ = IntArray(n) { it }, subcircuit = true)
        return Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, (n - 1).toLong()) },
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `single included node yields positive cost`() {
        // One included node (succ[0]=1) cannot form a cycle on its own; rest are self-loops.
        val problem = problem(4)
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 3)
        state.recompute()
        assertTrue(state.intPayload[0] > 0, "one included non-cyclic node must have positive cost")
        assertTrue(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `full N-cycle has zero cost`() {
        // All nodes included in a single Hamiltonian cycle.
        val n = 4
        val problem = problem(n)
        val state = LocalSearchState(problem, Random(0))
        for (i in 0 until n) state.assignment.setInt(i, ((i + 1) % n).toLong())
        state.recompute()
        assertEquals(0, state.intPayload[0], "a full N-cycle should satisfy Subcircuit with zero cost")
        assertFalse(state.factors[0].isViolated(state, 0))
    }

    @Test
    fun `deltaIfIntSet predicts cost change accurately`() {
        // Start with a valid 2-cycle {0,1} and two excluded nodes. Then check delta for
        // including node 2 (succ[2]=3) — this breaks the valid subcircuit.
        val n = 4
        val problem = problem(n)
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 3)
        state.recompute()
        assertEquals(0, state.intPayload[0], "valid 2-cycle should start at zero cost")

        // Predict delta for setting succ[2]=3 (including node 2, pointing at excluded 3).
        val predicted = state.factors[0].deltaIfIntSet(state, 0, intVar = 2, newValue = 3)
        state.apply(IntSet(2, 3))
        val actual = state.intPayload[0]
        assertEquals(actual, predicted, "predicted delta must equal actual cost change")
        assertTrue(actual > 0, "including a node pointing to an excluded node should raise cost")
    }

    @Test
    fun `two disjoint included cycles have higher cost than one`() {
        // 6 nodes: two 3-cycles vs. one 6-cycle.
        val n = 6
        val factor = Circuit(succ = IntArray(n) { it }, subcircuit = true)
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, (n - 1).toLong()) },
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem, Random(0))

        // One valid 6-cycle.
        for (i in 0 until n) state.assignment.setInt(i, ((i + 1) % n).toLong())
        state.recompute()
        val oneCycleCost = state.intPayload[0]
        assertEquals(0, oneCycleCost, "one 6-cycle should have zero cost")

        // Two disjoint 3-cycles.
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 0)
        state.assignment.setInt(3, 4)
        state.assignment.setInt(4, 5)
        state.assignment.setInt(5, 3)
        state.recompute()
        val twoCycleCost = state.intPayload[0]
        assertTrue(twoCycleCost > 0, "two disjoint cycles should have positive cost")
    }
}
