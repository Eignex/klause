package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationResult.Implied
import com.eignex.klause.solver.propagation.PropagationResult.Unsat
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
    fun `propagation rejects a successor pointing to a pinned-excluded node`() {
        // #90: succ[0] = 2 but node 2 is pinned excluded (succ[2] = 2). An excluded node has no
        // predecessor in the cycle, so this is a propagated conflict.
        val problem = fourNodeProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 2, 2 to 2)))
        assertTrue(result is Unsat, "pointing at a pinned-excluded node should be Unsat; got $result")
    }

    @Test
    fun `propagation rejects a premature closed sub-cycle that strands an included node`() {
        // #90: 0↔1 is a closed 2-cycle of fixed edges; node 2 is pinned to a non-self successor so
        // it must be on the cycle, but the cycle is already sealed — infeasible.
        val problem = fourNodeProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 0, 2 to 3)))
        assertTrue(result is Unsat, "a sealed sub-cycle leaving an included node out should be Unsat; got $result")
    }

    @Test
    fun `propagation forbids closing a chain that would strand included nodes`() {
        // #90 chain-walk: fixed edges 0→1 and 2→3 make nodes 0 and 2 included. For succ[1],
        // pigeonhole removes 1 (claimed by node 0) and 3 (claimed by node 2), self-looping to 1 is
        // already excluded, and the chain-walk forbids 0 (closing the {0,1} sub-cycle would strand
        // the other included nodes) — leaving 2 as the only option, so succ[1] is forced to 2.
        val problem = fourNodeProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 2 to 3)))
        assertTrue(result is Implied, "feasible chain config should propagate, not fail; got $result")
        assertEquals(2, result.intValueOrNull(1), "succ[1] must be forced to 2 (0 closes a sub-cycle, 1/3 taken)")
    }

    @Test
    fun `propagation accepts a valid pinned 2-cycle with two excluded nodes`() {
        // Guard against false conflicts: 0↔1 cycle, 2 and 3 excluded — a valid subcircuit.
        val problem = fourNodeProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 0, 2 to 2, 3 to 3)))
        assertTrue(result is Implied, "a valid pinned subcircuit must not be rejected; got $result")
    }

    @Test
    fun `propagation rejects a larger pinned sub-cycle that strands an included node`() {
        // N=5: pin a closed 3-cycle 0->1->2->0 and force node 3 onto the cycle (succ[3]=4, a
        // non-self successor makes 3 definitely included). The sealed 3-cycle can't absorb the
        // extra included node, so the singleton-walk check (includedCount > cycleLen 3) fails.
        val factor = Subcircuit(succ = intArrayOf(0, 1, 2, 3, 4))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 2, 2 to 0, 3 to 4)))
        assertTrue(result is Unsat, "a sealed 3-cycle stranding an included node should be Unsat; got $result")
    }

    @Test
    fun `LS solver finds a valid subcircuit`() {
        val problem = fourNodeProblem()
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 13L)).assignment
        assertTrue(sample != null, "LS should find a valid Subcircuit configuration")
    }
}
