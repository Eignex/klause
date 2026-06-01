package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubcircuitTest {

    private fun fourNodeProblem(): Problem {
        val factor = Subcircuit(succ = intArrayOf(0, 1, 2, 3))
        return Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `all self-loops is the empty subcircuit and satisfies the factor`() {
        val problem = fourNodeProblem()
        val state = LocalSearchState(problem, Random(0))
        // All vars self-loop → all nodes excluded → empty subcircuit, valid.
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 3)
        state.recompute()
        assertEquals(0, state.cost, "all-excluded should satisfy Subcircuit")
    }

    @Test
    fun `two-node subcircuit with two excluded nodes is valid`() {
        val problem = fourNodeProblem()
        val state = LocalSearchState(problem, Random(0))
        // Nodes 0 and 1 form a cycle; 2 and 3 excluded.
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 3)
        state.recompute()
        assertEquals(0, state.cost, "valid 2-cycle with 2 excluded should satisfy Subcircuit")
    }

    @Test
    fun `pointing to an excluded node is violated`() {
        val problem = fourNodeProblem()
        val state = LocalSearchState(problem, Random(0))
        // succ[0]=2 but succ[2]=2 (excluded). Pointing to an excluded node breaks the chain.
        state.assignment.setInt(0, 2)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 3)
        state.recompute()
        assertTrue(state.cost > 0, "successor pointing to excluded node should violate Subcircuit")
    }

    @Test
    fun `two disjoint cycles among included nodes is violated`() {
        val factor = Subcircuit(succ = intArrayOf(0, 1, 2, 3, 4, 5))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 5) },
            factors = arrayOf<Factor>(factor),
        )
        val state = LocalSearchState(problem, Random(0))
        // Two 3-cycles: 0→1→2→0 and 3→4→5→3. All included, but two cycles → violated.
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 0)
        state.assignment.setInt(3, 4)
        state.assignment.setInt(4, 5)
        state.assignment.setInt(5, 3)
        state.recompute()
        assertTrue(state.cost > 0, "two disjoint included cycles should violate Subcircuit")
    }

    @Test
    fun `LS solver finds a valid subcircuit`() {
        val problem = fourNodeProblem()
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 13L)).assignment
        assertTrue(sample != null, "LS should find a valid Subcircuit configuration")
    }
}
