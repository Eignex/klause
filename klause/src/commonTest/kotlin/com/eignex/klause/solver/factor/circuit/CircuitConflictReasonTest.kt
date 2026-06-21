package com.eignex.klause.solver.factor.circuit

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.circuit.Circuit
import com.eignex.klause.solver.factor.circuit.Subcircuit
import com.eignex.klause.solver.factor.circuit.SuccessorCycleFactor
import com.eignex.klause.solver.propagation.PropagationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #651: [Circuit] and [Subcircuit] now inherit a [Factor.conflictReason] override from
 * [SuccessorCycleFactor] — the bound atoms of every successor var. Both reason globally over the
 * whole successor array (range/self-loop shaving, the AllDifferent pigeonhole, the sub-tour /
 * unreachability scan) and prune only at domain endpoints, so the current `succ` bounds are a
 * sound nogood over the successor atoms. Previously the failure fell through to the coarse default
 * bool-pins reason. Tests: (1) the reason is a sound non-empty witness; (2) full enumeration under
 * CDCL learning matches brute force for Circuit and Subcircuit, so the new nogood prunes nothing.
 */
class CircuitConflictReasonTest {

    @Test
    fun `circuit sub-tour conflict reason is a sound nonempty witness`() {
        // n=3. Pinning succ[0]=1 and succ[1]=0 makes nodes {0,1} a closed 2-cycle, so node 2 has
        // no surviving successor (both 0 and 1 are claimed) — the propagator wipes succ[2].
        val factor = Circuit(succ = intArrayOf(0, 1, 2))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(factor),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        assertTrue(state.tightenIntMin(0, 1) && state.tightenIntMax(0, 1), "succ[0] = 1")
        assertTrue(state.tightenIntMax(1, 0), "succ[1] = 0")
        assertFalse(problem.propagators[0].propagate(state, 0), "the 2-cycle over {0,1} leaves node 2 unplaceable")

        val reason = problem.propagators[0].conflictReason(state, 0)
        assertTrue(reason != null && reason.isNotEmpty(), "must yield a non-empty clause-form reason")
        for (lit in reason) {
            assertTrue(state.litFalse(lit), "every reason literal must be false at conflict time, lit=$lit")
        }
    }

    private fun enumerate(problem: Problem, seed: Long): HashSet<List<Int>> = BacktrackSolver(problem)
        .enumerate(BacktrackParams(randomSeed = seed, variableSelector = Vsids()))
        .take(100_000)
        .map { it.ints.toList() }
        .toHashSet()

    private fun problemOf(factor: Factor, n: Int) = Problem(
        numBoolVars = 0,
        numIntVars = n,
        intDomains = Array(n) { IntDomain(0, n - 1) },
        factors = arrayOf(factor),
    )

    /** Decode the [code]-th function [0,n) -> [0,n) (n^n of them) into a successor array. */
    private fun decodeFunction(code: Int, n: Int): IntArray {
        val s = IntArray(n)
        var c = code
        for (i in 0 until n) {
            s[i] = c % n
            c /= n
        }
        return s
    }

    private fun isSingleCycleOver(s: IntArray, nodes: List<Int>): Boolean {
        if (nodes.map { s[it] }.toSet() != nodes.toSet()) return false // must permute the node set
        val visited = HashSet<Int>()
        var cur = nodes[0]
        repeat(nodes.size) {
            visited.add(cur)
            cur = s[cur]
        }
        return visited.size == nodes.size && cur == nodes[0]
    }

    @Test
    fun `circuit enumerate matches brute force`() {
        val n = 4
        for (seed in 1L..5L) {
            val brute = HashSet<List<Int>>()
            for (code in 0 until pow(n, n)) {
                val s = decodeFunction(code, n)
                if (isSingleCycleOver(s, (0 until n).toList())) brute.add(s.toList())
            }
            assertEquals(brute, enumerate(problemOf(Circuit(IntArray(n) { it }), n), seed), "seed=$seed circuit")
        }
    }

    @Test
    fun `subcircuit enumerate matches brute force`() {
        val n = 3
        for (seed in 1L..5L) {
            val brute = HashSet<List<Int>>()
            for (code in 0 until pow(n, n)) {
                val s = decodeFunction(code, n)
                val included = (0 until n).filter { s[it] != it }
                val valid = included.isEmpty() || isSingleCycleOver(s, included)
                if (valid) brute.add(s.toList())
            }
            assertEquals(brute, enumerate(problemOf(Subcircuit(IntArray(n) { it }), n), seed), "seed=$seed subcircuit")
        }
    }

    private fun pow(base: Int, exp: Int): Int {
        var r = 1
        repeat(exp) { r *= base }
        return r
    }
}
