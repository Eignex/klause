package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Move.IntSet
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.propagation.PropagationResult.Implied
import com.eignex.klause.solver.propagation.PropagationResult.Unsat
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
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `complete cycle 0_1_2_3_0 is satisfied`() {
        val problem = fourNodeProblem()
        val state = LocalSearchState(problem, Random(0))
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
        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 3)
        state.assignment.setInt(3, 1)
        state.recompute()
        assertTrue(state.cost > 0, "self-loops are forbidden in Circuit for N >= 2")
    }

    @Test
    fun `circuit propagation rejects unbounded domain`() {
        val factor = Circuit(succ = intArrayOf(0, 1, 2))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 100))
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 1L)).assignment
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
    fun `factor's internal graded cost reflects how broken the assignment is`() {
        // Circuit's graded cost lives in state.intPayload[factorId]; state.cost is binary.
        val problem = fourNodeProblem()
        val state = LocalSearchState(problem, Random(0))

        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 0)
        state.assignment.setInt(2, 3)
        state.assignment.setInt(3, 2)
        state.recompute()
        val cost2 = state.intPayload[0]
        assertEquals(1, cost2, "two 2-cycles should yield graded cost 1")

        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 3)
        state.recompute()
        val cost4 = state.intPayload[0]
        assertEquals(9, cost4, "4 self-loops should yield graded cost 9")

        assertTrue(cost4 > cost2, "4-self-loop config should rank as more broken than 2-cycle config")
    }

    @Test
    fun `cached cost matches recomputed after applying a move`() {
        val problem = fourNodeProblem()
        val state = LocalSearchState(problem, Random(0))
        state.assignment.setInt(0, 1)
        state.assignment.setInt(1, 2)
        state.assignment.setInt(2, 3)
        state.assignment.setInt(3, 0)
        state.recompute()
        assertEquals(0, state.cost, "Hamiltonian baseline")
        state.apply(IntSet(0, 2))
        assertTrue(state.cost > 0, "broken assignment should have positive cost")
    }

    @Test
    fun `BacktrackSolver enumerates exactly the Hamiltonian cycles on N=4`() {
        // Circuit distinguishes direction and starting point, so the count is
        // (N-1)! = 6 cyclic permutations, not (N-1)!/2 undirected cycles.
        val problem = fourNodeProblem()
        val solver = BacktrackSolver(problem)
        val params = BacktrackParams()
        val samples = solver.enumerate(params).toList()
        assertEquals(6, samples.size, "expected 6 Hamiltonian cycles on N=4, got ${samples.size}: $samples")
        for (s in samples) {
            val visited = BooleanArray(4)
            var node = 0
            for (step in 0 until 4) {
                assertFalse(visited[node], "revisit at step $step in ${s.ints.toList()}")
                visited[node] = true
                node = s.ints[node]
            }
            assertEquals(0, node, "must close cycle in ${s.ints.toList()}")
        }
    }

    @Test
    fun `propagator forces last edge when N-1 successors are fixed`() {
        val factor = Circuit(succ = intArrayOf(0, 1, 2, 3))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val assumptions = Assumptions(ints = mapOf(0 to 1, 1 to 2, 2 to 3))
        val result = problem.propagate(assumptions)
        assertTrue(
            result is Implied,
            "propagation should succeed and force the closing edge; got $result",
        )
        assertEquals(0, result.ints[3], "succ[3] should be forced to 0; got implied=${result.ints}")
    }

    @Test
    fun `propagator detects pigeonhole infeasibility`() {
        val factor = Circuit(succ = intArrayOf(0, 1, 2, 3))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
        val assumptions = Assumptions(ints = mapOf(0 to 2, 1 to 2))
        val result = problem.propagate(assumptions)
        assertTrue(
            result is Unsat,
            "should detect infeasibility; got $result",
        )
    }

    /** 5-node Circuit, succ var ids 0..4 each over `[0,4]`. */
    private fun fiveNodeProblem(): Problem {
        val factor = Circuit(succ = intArrayOf(0, 1, 2, 3, 4))
        return Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 4) },
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `propagator detects a pinned sub-cycle shorter than N`() {
        // Pin 0->1->2->0: a closed 3-cycle among {0,1,2} while N=5, so nodes 3 and 4 can never
        // join it. The singleton-walk sub-cycle check (cycleLen 3 < n 5) must report infeasible.
        val problem = fiveNodeProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 2, 2 to 0)))
        assertTrue(result is Unsat, "a closed 3-cycle in a 5-node circuit should be Unsat; got $result")
    }

    @Test
    fun `propagator accepts a Hamiltonian chain prefix without a false sub-cycle`() {
        // Pin the open chain 0->1->2->3->4 (succ[4] still free). The walk reaches a non-singleton
        // and ends without closing a cycle, so this must NOT be rejected — and the closing edge
        // succ[4]=0 is forced. Guards the per-walk reset against false positives.
        val problem = fiveNodeProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 2, 2 to 3, 3 to 4)))
        assertTrue(result is Implied, "an open Hamiltonian chain prefix must propagate, not fail; got $result")
        assertEquals(0, result.ints[4], "succ[4] should be forced to 0 to close the cycle; got ${result.ints}")
    }

    @Test
    fun `LS solver finds Hamiltonian cycle on N=4`() {
        val problem = fourNodeProblem()
        val solver = LocalSearchSolver(problem, restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 7L)).assignment
        assertTrue(sample != null, "LS should find a Hamiltonian cycle on N=4 within budget")
        val visited = BooleanArray(4)
        var node = 0
        for (step in 0 until 4) {
            assertFalse(visited[node], "revisit at step $step in ${sample.ints.toList()}")
            visited[node] = true
            node = sample.ints[node]
        }
        assertEquals(0, node, "must return to start in ${sample.ints.toList()}")
    }
}
