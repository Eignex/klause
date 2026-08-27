package com.eignex.klause.factor.circuit

import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.Move.IntSet
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircuitInvariantTest {

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
        // state.cost is Σ factorDegree, which for Circuit is read straight from intPayload.
        assertEquals(1, state.intPayload[0], "cost should equal the factor's graded payload")
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
    fun `LS sample on an oversized successor domain forms a valid tour`() {
        val factor = Circuit(succ = intArrayOf(0, 1, 2))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf<Factor>(factor),
        )
        val solver = LocalSearchSolver(problem.bake(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 100))
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 1L)).assignment
        if (sample != null) {
            val visited = BooleanArray(3)
            var node = 0
            for (step in 0 until 3) {
                assertFalse(visited[node], "revisit at step $step")
                visited[node] = true
                node = sample.ints[node].toInt()
            }
            assertEquals(0, node, "must return to start")
        }
    }

    @Test
    fun `4 self-loops rank as more broken than two disjoint 2-cycles`() {
        val problem = fourNodeProblem()
        val state = LocalSearchState(problem, Random(0))

        state.assignment.setInt(0, 0)
        state.assignment.setInt(1, 1)
        state.assignment.setInt(2, 2)
        state.assignment.setInt(3, 3)
        state.recompute()
        val cost4 = state.intPayload[0]
        assertEquals(9, cost4, "4 self-loops should yield graded cost 9")

        // The two-2-cycles config above (`sub-cycle 0_1_0...`) has graded cost 1.
        assertTrue(cost4 > 1, "4-self-loop config should rank as more broken than 2-cycle config")
    }

    @Test
    fun `applying a move that breaks the cycle raises the cost`() {
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
    fun `LS solver finds Hamiltonian cycle on N=4`() {
        val problem = fourNodeProblem()
        val solver = LocalSearchSolver(problem.bake(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 7L)).assignment
        assertTrue(sample != null, "LS should find a Hamiltonian cycle on N=4 within budget")
        val visited = BooleanArray(4)
        var node = 0
        for (step in 0 until 4) {
            assertFalse(visited[node], "revisit at step $step in ${sample.ints.toList()}")
            visited[node] = true
            node = sample.ints[node].toInt()
        }
        assertEquals(0, node, "must return to start in ${sample.ints.toList()}")
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
    fun `every structured circuit move preserves the Hamiltonian tour`() {
        val n = 8
        for (seed in longArrayOf(1L, 2L, 3L, 13L, 99L)) {
            val state = LocalSearchState(nNodeCircuit(n), Random(seed))
            seedTour(state, n)
            assertEquals(0, state.cost, "the seed tour must be feasible")
            val sink = MoveSink()
            state.factors[0].proposeExtendedStructuredMoves(state, 0, sink)
            for (move in sink.list) {
                val check = LocalSearchState(nNodeCircuit(n), Random(0))
                seedTour(check, n)
                check.apply(move)
                assertEquals(0, check.cost, "structured move $move broke the tour")
            }
        }
    }

    @Test
    fun `2-opt reversals reach compounds a 3-edge swap cannot`() {
        val n = 8
        var maxParts = 0
        for (seed in 0L until 40L) {
            val state = LocalSearchState(nNodeCircuit(n), Random(seed))
            seedTour(state, n)
            val sink = MoveSink()
            state.factors[0].proposeExtendedStructuredMoves(state, 0, sink)
            for (m in sink.list) if (m is Move.Compound) maxParts = maxOf(maxParts, m.parts.size)
        }
        assertTrue(maxParts > 3, "reversals must emit compounds longer than a 3-edge swap, got max $maxParts")
    }

    private fun fourNodeSubcircuitProblem(): Problem {
        val factor = Circuit(succ = intArrayOf(0, 1, 2, 3), subcircuit = true)
        return Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `valid subcircuit configurations satisfy the factor`() {
        val cases = listOf(
            // All vars self-loop → all nodes excluded → empty subcircuit, valid.
            longArrayOf(0, 1, 2, 3) to "all-excluded should satisfy Subcircuit",
            // Nodes 0 and 1 form a cycle; 2 and 3 excluded.
            longArrayOf(1, 0, 2, 3) to "valid 2-cycle with 2 excluded should satisfy Subcircuit",
        )
        for ((succ, message) in cases) {
            val problem = fourNodeSubcircuitProblem()
            val state = LocalSearchState(problem, Random(0))
            for (i in succ.indices) state.assignment.setInt(i, succ[i])
            state.recompute()
            assertEquals(0, state.cost, message)
        }
    }

    @Test
    fun `pointing to an excluded node is violated`() {
        val problem = fourNodeSubcircuitProblem()
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
        val factor = Circuit(succ = intArrayOf(0, 1, 2, 3, 4, 5), subcircuit = true)
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
        val problem = fourNodeSubcircuitProblem()
        val solver = LocalSearchSolver(problem.bake(), restartPolicy = FixedCadenceRestart(maxFlipsBeforeRestart = 200))
        val sample = solver.sample(LocalSearchParams(maxFlips = 10_000L, randomSeed = 13L)).assignment
        assertTrue(sample != null, "LS should find a valid Subcircuit configuration")
    }

    // A 5-node active cycle (0..4) with node 5 excluded (self-loop).
    private fun subcircuitWithExcluded(): Problem = Problem(
        numBoolVars = 0,
        numIntVars = 6,
        intDomains = Array(6) { IntDomain(0, 5) },
        factors = arrayOf<Factor>(Circuit(succ = intArrayOf(0, 1, 2, 3, 4, 5), subcircuit = true)),
    )

    private fun seededSubcircuit(seed: Long): LocalSearchState {
        val state = LocalSearchState(subcircuitWithExcluded(), Random(seed))
        for (i in 0 until 5) state.assignment.setInt(i, ((i + 1) % 5).toLong())
        state.assignment.setInt(5, 5)
        state.recompute()
        return state
    }

    @Test
    fun `every structured subcircuit move preserves the active sub-tour`() {
        for (seed in longArrayOf(1L, 2L, 3L, 7L, 11L)) {
            val state = seededSubcircuit(seed)
            assertEquals(0, state.cost, "active 5-cycle with one excluded node must be feasible")
            val sink = MoveSink()
            state.factors[0].proposeExtendedStructuredMoves(state, 0, sink)
            for (move in sink.list) {
                val check = seededSubcircuit(0)
                check.apply(move)
                assertEquals(0, check.cost, "structured subcircuit move $move broke the active sub-tour")
            }
        }
    }
}
