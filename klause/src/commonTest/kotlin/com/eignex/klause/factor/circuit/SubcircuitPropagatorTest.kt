package com.eignex.klause.factor.circuit

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.PropagationResult.Implied
import com.eignex.klause.propagation.PropagationResult.Unsat
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SubcircuitPropagatorTest {

    /** Is `next` (length n, each in [0,n)) a valid sub-circuit: the non-self-loop nodes form exactly
     *  one cycle (all self-loops — the empty sub-circuit — is allowed)? */
    private fun isValidSubcircuit(next: IntArray): Boolean {
        val n = next.size
        val included = (0 until n).filter { next[it] != it }
        if (included.isEmpty()) return true
        val visited = HashSet<Int>()
        var cur = included[0]
        while (cur !in visited) {
            if (next[cur] == cur) return false // stepped onto an excluded node
            visited.add(cur)
            cur = next[cur]
        }
        return cur == included[0] && visited.size == included.size
    }

    @Test
    fun `BacktrackSolver matches brute oracle on restricted-domain subcircuits`() {
        // End-to-end: a false conflict from the strong-connectivity check would surface as a false
        // UNSAT here (a sub-circuit exists but the solver reports none). Brute-counts the solutions
        // over each random restricted domain and checks the solver's SAT/UNSAT verdict agrees.
        val rng = Random(0x5BC141)
        repeat(3000) { _ ->
            val n = rng.nextInt(3, 6) // 3..5 nodes
            val los = IntArray(n)
            val his = IntArray(n)
            for (i in 0 until n) {
                val a = rng.nextInt(0, n)
                val b = rng.nextInt(0, n)
                los[i] = minOf(a, b)
                his[i] = maxOf(a, b)
            }
            var brute = 0
            val cur = los.copyOf()
            while (true) {
                if (isValidSubcircuit(cur)) brute++
                var i = 0
                while (i < n) {
                    if (cur[i] < his[i]) {
                        cur[i]++
                        break
                    }
                    cur[i] = los[i]
                    i++
                }
                if (i == n) break
            }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(los[it].toLong(), his[it].toLong()) },
                factors = arrayOf<Factor>(Circuit(succ = IntArray(n) { it }, subcircuit = true)),
            )
            val result = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 1L))
            if (brute > 0) {
                assertTrue(result is SolveResult.Sat, "false UNSAT: los=${los.toList()} his=${his.toList()}")
            } else {
                assertTrue(result is SolveResult.Unsat, "false SAT: los=${los.toList()} his=${his.toList()}")
            }
        }
    }

    private fun problem(n: Int, lo: Int = 0, hi: Int = n - 1): Problem {
        val factor = Circuit(succ = IntArray(n) { it }, subcircuit = true)
        return Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(lo.toLong(), hi.toLong()) },
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `subcircuit subscribes to bound and value events on all succ vars`() {
        val n = 3
        val factor = Circuit(succ = IntArray(n) { it }, subcircuit = true)
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
        val result = problem.propagate(Assumptions(ints = (0 until n).associate { it to it.toLong() }))
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
        val factor = Circuit(succ = intArrayOf(0, 1, 2), subcircuit = true)
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
        val found = BacktrackSolver(problem.bake()).enumerate(BacktrackParams(randomSeed = 1L))
            .take(10_000).map { s -> s.ints.map { it.toInt() } }.toHashSet()
        assertEquals(brute, found, "n=2 subcircuit must enumerate exactly 2 valid assignments")
    }
}
