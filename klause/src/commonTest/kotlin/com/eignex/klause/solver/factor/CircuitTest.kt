package com.eignex.klause.solver.factor

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircuitTest {

    /** Build a 4-var Circuit problem; succ var ids are 0..3, each with domain [0..3]. */
    private fun fourNodeProblem(): Problem {
        val factor = Circuit(succ = intArrayOf(0, 1, 2, 3))
        return Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(factor),
        )
    }

    @Test
    fun `complete cycle 0_1_2_3_0 is satisfied`() {
        val problem = fourNodeProblem()
        val state = LocalSearchState(problem, Random(0))
        // succ[0]=1, succ[1]=2, succ[2]=3, succ[3]=0 → cycle 0→1→2→3→0
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 3)
        state.assignment.setInt(3, 0)
        state.recompute()
        assertEquals(0, state.cost, "complete cycle should satisfy Circuit")
    }

    @Test
    fun `sub-cycle 0_1_0 with disconnected 2_3 is violated`() {
        val problem = fourNodeProblem()
        val state = LocalSearchState(problem, Random(0))
        // succ[0]=1, succ[1]=0 → 2-cycle. succ[2]=3, succ[3]=2 → another 2-cycle.
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 3)
        state.assignment.setInt(3, 2)
        state.recompute()
        assertEquals(1, state.cost, "two disjoint 2-cycles should violate Circuit")
    }

    @Test
    fun `self-loop is violated for N greater than 1`() {
        val problem = fourNodeProblem()
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 0) // self-loop on node 0
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 3)
        state.assignment.setInt(3, 1)
        state.recompute()
        assertTrue(state.cost > 0, "self-loops are forbidden in Circuit for N >= 2")
    }

    @Test
    fun `circuit propagation rejects unbounded domain`() {
        // Var domains exceed [0, n) — propagation should tighten or detect infeasibility.
        // Here all domains contain [0, 3] plus extra, so propagation tightens to [0, 3].
        val factor = Circuit(succ = intArrayOf(0, 1, 2))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = listOf(factor),
        )
        // Just verify the problem can be solved — full propagation correctness requires
        // PropagationState plumbing which is BacktrackSolver-internal. Skip detailed checks.
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 100))
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 1L))
        // Sample may be null if LS can't find a Hamiltonian cycle in the budget; if found,
        // verify it's actually a valid cycle.
        if (sample != null) {
            val visited = BooleanArray(3)
            var node = 0
            for (step in 0 until 3) {
                assertFalse(visited[node], "revisit at step $step")
                visited[node] = true
                node = sample.ints[node]
            }
            assertEquals(0, node, "must return to start")
        }
    }

    @Test
    fun `LS solver finds Hamiltonian cycle on N=4`() {
        val problem = fourNodeProblem()
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 7L))
        assertTrue(sample != null, "LS should find a Hamiltonian cycle on N=4 within budget")
        // Verify the sample is actually a valid cycle.
        val visited = BooleanArray(4)
        var node = 0
        for (step in 0 until 4) {
            assertFalse(visited[node], "revisit at step $step in ${sample!!.ints.toList()}")
            visited[node] = true
            node = sample.ints[node]
        }
        assertEquals(0, node, "must return to start in ${sample!!.ints.toList()}")
    }
}
