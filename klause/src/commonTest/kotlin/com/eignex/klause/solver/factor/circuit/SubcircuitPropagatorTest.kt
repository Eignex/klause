package com.eignex.klause.solver.factor.circuit

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.circuit.Subcircuit
import com.eignex.klause.solver.propagation.IntEvent
import com.eignex.klause.solver.propagation.PropagationResult.Implied
import com.eignex.klause.solver.propagation.PropagationResult.Unsat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubcircuitPropagatorTest {

    private fun problem(n: Int, lo: Int = 0, hi: Int = n - 1): Problem {
        val factor = Subcircuit(succ = IntArray(n) { it })
        return Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(lo, hi) },
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `subcircuit subscribes to bound and value events on all succ vars`() {
        val n = 3
        val factor = Subcircuit(succ = IntArray(n) { it })
        val watches = factor.asPropagator().initialIntEventWatches
        assertNotNull(watches, "SubcircuitPropagator must opt into typed int events")
        val pairs = watches.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet()
        for (v in 0 until n) {
            assertTrue(pairs.contains(v to IntEvent.LB_RAISED), "missing LB_RAISED for var $v")
            assertTrue(pairs.contains(v to IntEvent.UB_LOWERED), "missing UB_LOWERED for var $v")
            assertTrue(pairs.contains(v to IntEvent.VALUE_REMOVED), "missing VALUE_REMOVED for var $v")
            assertTrue(pairs.contains(v to IntEvent.FIXED), "missing FIXED for var $v")
        }
    }

    @Test
    fun `all self-loops is accepted by propagation`() {
        // Empty subcircuit: every node's self-loop value is forced in domain.
        val n = 3
        val problem = problem(n)
        val result = problem.propagate(Assumptions(ints = (0 until n).associate { it to it }))
        assertTrue(result is Implied, "all self-loops (empty subcircuit) must propagate without conflict; got $result")
    }

    @Test
    fun `propagation rejects a claimed successor taken by two included nodes`() {
        // succ[0]=2 and succ[1]=2 — two nodes claim the same successor. Infeasible.
        val problem = problem(4)
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 2, 1 to 2)))
        assertTrue(result is Unsat, "two nodes claiming same successor should be Unsat; got $result")
    }

    @Test
    fun `propagation forces closing edge when only one valid target remains`() {
        // Fixed: succ[0]=1, succ[1]=2. Node 2 must close back to 0 (others claimed or excluded).
        // Domain for succ[2] includes 0, 1, 2 — after propagation succ[2]=0 should be forced.
        val factor = Subcircuit(succ = intArrayOf(0, 1, 2))
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf<Factor>(factor),
        )
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 2)))
        assertTrue(result is Implied, "chain 0→1→2 must propagate; got $result")
        assertEquals(0, result.ints[2], "succ[2] must be forced to 0 to close the cycle; got ${result.ints}")
    }

    @Test
    fun `n=2 valid subcircuits are exactly self-loops and 2-cycle`() {
        // n=2: valid assignments are {0→0, 1→1} and {0→1, 1→0}.
        val brute = setOf(listOf(0, 1), listOf(1, 0))
        val problem = problem(2)
        val found = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 1L))
            .take(10_000).map { it.ints.toList() }.toHashSet()
        assertEquals(brute, found, "n=2 subcircuit must enumerate exactly 2 valid assignments")
    }
}
