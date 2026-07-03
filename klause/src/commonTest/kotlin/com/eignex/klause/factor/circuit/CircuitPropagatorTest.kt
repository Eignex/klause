package com.eignex.klause.factor.circuit

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.Vsids
import com.eignex.klause.factor.ConflictReasonOracle
import com.eignex.klause.factor.FactorPropagationOracle
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.circuit.Subcircuit
import com.eignex.klause.factor.circuit.SuccessorCycleFactor
import com.eignex.klause.propagation.PropagationResult.Implied
import com.eignex.klause.propagation.PropagationResult.Unsat
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CircuitPropagatorTest {

    @Test
    fun `strong connectivity rejects a candidate graph with an inescapable component`() {
        // Nodes {3,4,5} only point within themselves, so once the cycle enters that block (via 0→3)
        // it can never return — no Hamiltonian circuit exists. No edge is fixed, so the subtour /
        // chain checks see nothing; only the strong-connectivity (SCC) condition rules it out.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(1, 3),
                IntDomain(0, 2),
                IntDomain(0, 1),
                IntDomain(4, 5),
                IntDomain(3, 5),
                IntDomain(3, 4),
            ),
            factors = arrayOf<Factor>(Circuit(succ = intArrayOf(0, 1, 2, 3, 4, 5))),
        )
        assertTrue(problem.propagate() is Unsat, "an inescapable component must be rejected by SCC reasoning")
    }

    @Test
    fun `subtour conflict reason is a sound nogood citing only the subtour edges`() {
        // Globally satisfiable (4-node Hamiltonian cycles exist). A decision fixes succ[0]=1 and
        // succ[1]=0, a premature 2-cycle. The sharp reason must cite only those two successor vars,
        // not the idle var 2 (tightened but uninvolved), and must be entailed by the circuit.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 2), IntDomain(0, 3)),
            factors = arrayOf<Factor>(Circuit(succ = intArrayOf(0, 1, 2, 3))),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(0, 1))
        check(state.tightenIntMax(0, 1)) // succ[0]=1
        check(state.tightenIntMin(1, 0))
        check(state.tightenIntMax(1, 0)) // succ[1]=0
        check(state.tightenIntMax(2, 2)) // idle var 2 tightened → a coarse reason would cite it
        state.currentFactor = 0
        assertFalse(problem.propagators[0].propagate(state, 0))
        val reason = problem.propagators[0].conflictReason(state, 0)!!
        val citedVars = reason.map { state.atomIntVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertTrue(citedVars.all { it == 0 || it == 1 }, "reason must cite only the subtour edges, got $citedVars")
        assertTrue(2 !in citedVars, "idle successor var 2 must not appear in the sharp reason")
        ConflictReasonOracle.assertEntailed(problem, state, 0, "circuit-subtour")
    }

    @Test
    fun `circuit filtering never over-prunes`() {
        // Brute-force oracle: the SCC condition (and the rest of the pass) must not exclude any value
        // that lies on some Hamiltonian circuit. n=4 ⇒ 4^4 = 256 assignments, under the brute cap.
        val rng = Random(0xC141)
        repeat(400) { iter ->
            val nNodes = 4
            val doms = Array(nNodes) {
                val a = rng.nextInt(nNodes)
                val b = rng.nextInt(nNodes)
                IntDomain(minOf(a, b), maxOf(a, b))
            }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = nNodes,
                intDomains = doms,
                factors = arrayOf<Factor>(Circuit(succ = IntArray(nNodes) { it })),
            )
            FactorPropagationOracle.assertSound(problem, "circuit#$iter")
        }
    }

    // --- from CircuitBruteTest ---

    /** Is `next` (length n, each in [0,n)) a single Hamiltonian cycle? */
    private fun isHamiltonian(next: IntArray): Boolean {
        val n = next.size
        val visited = BooleanArray(n)
        var cur = 0
        repeat(n) {
            if (next[cur] == cur) return false // self-loop
            if (visited[cur]) return false
            visited[cur] = true
            cur = next[cur]
        }
        return cur == 0 && visited.all { it }
    }

    /** Enumerate every assignment within [los..his] and count Hamiltonian cycles. */
    private fun bruteSatCount(los: IntArray, his: IntArray): Int {
        val n = los.size
        val cur = los.copyOf()
        var count = 0
        while (true) {
            if (isHamiltonian(cur)) count++
            // increment mixed-radix over [los[i]..his[i]]
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
        return count
    }

    @Test
    fun `BacktrackSolver matches brute oracle on restricted-domain circuits`() {
        val rng = Random(20260603)
        var checked = 0
        repeat(4000) {
            val n = rng.nextInt(3, 6) // 3..5 nodes
            val los = IntArray(n)
            val his = IntArray(n)
            for (i in 0 until n) {
                val a = rng.nextInt(0, n)
                val b = rng.nextInt(0, n)
                los[i] = minOf(a, b)
                his[i] = maxOf(a, b)
            }
            val brute = bruteSatCount(los, his)

            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { v -> IntDomain(los[v], his[v]) },
                factors = arrayOf<Factor>(Circuit(succ = IntArray(n) { v -> v })),
            )
            val result = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 1L))
            checked++

            if (brute > 0) {
                assertTrue(
                    result is SolveResult.Sat,
                    "false UNSAT: n=$n los=${los.toList()} his=${his.toList()} has $brute Hamiltonian " +
                        "cycle(s) but BacktrackSolver returned $result",
                )
            } else {
                assertTrue(
                    result is SolveResult.Unsat,
                    "false SAT: n=$n los=${los.toList()} his=${his.toList()} has no Hamiltonian cycle " +
                        "but BacktrackSolver returned $result",
                )
            }
        }
        assertTrue(checked > 0)
    }

    // --- from CircuitConflictReasonTest ---

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

    // --- from CircuitTest (CP tests) ---

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

    // --- from SubcircuitTest (CP tests) ---

    private fun fourNodeSubcircuitProblem(): Problem {
        val factor = Subcircuit(succ = intArrayOf(0, 1, 2, 3))
        return Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = arrayOf<Factor>(factor),
        )
    }

    @Test
    fun `propagation rejects a successor pointing to a pinned-excluded node`() {
        // #90: succ[0] = 2 but node 2 is pinned excluded (succ[2] = 2). An excluded node has no
        // predecessor in the cycle, so this is a propagated conflict.
        val problem = fourNodeSubcircuitProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 2, 2 to 2)))
        assertTrue(result is Unsat, "pointing at a pinned-excluded node should be Unsat; got $result")
    }

    @Test
    fun `propagation rejects a premature closed sub-cycle that strands an included node`() {
        // #90: 0↔1 is a closed 2-cycle of fixed edges; node 2 is pinned to a non-self successor so
        // it must be on the cycle, but the cycle is already sealed — infeasible.
        val problem = fourNodeSubcircuitProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 1 to 0, 2 to 3)))
        assertTrue(result is Unsat, "a sealed sub-cycle leaving an included node out should be Unsat; got $result")
    }

    @Test
    fun `propagation forbids closing a chain that would strand included nodes`() {
        // #90 chain-walk: fixed edges 0→1 and 2→3 make nodes 0 and 2 included. For succ[1],
        // pigeonhole removes 1 (claimed by node 0) and 3 (claimed by node 2), self-looping to 1 is
        // already excluded, and the chain-walk forbids 0 (closing the {0,1} sub-cycle would strand
        // the other included nodes) — leaving 2 as the only option, so succ[1] is forced to 2.
        val problem = fourNodeSubcircuitProblem()
        val result = problem.propagate(Assumptions(ints = mapOf(0 to 1, 2 to 3)))
        assertTrue(result is Implied, "feasible chain config should propagate, not fail; got $result")
        assertEquals(2, result.intValueOrNull(1), "succ[1] must be forced to 2 (0 closes a sub-cycle, 1/3 taken)")
    }

    @Test
    fun `propagation accepts a valid pinned 2-cycle with two excluded nodes`() {
        // Guard against false conflicts: 0↔1 cycle, 2 and 3 excluded — a valid subcircuit.
        val problem = fourNodeSubcircuitProblem()
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
    fun `backtrack enumeration over the subcircuit equals brute force`() {
        // Soundness gate for the delta-gated propagator: enumerating under the CDCL backtracker fires
        // propagate repeatedly on one PropagationState — the delta fast path skips no-op re-fires, a
        // domain change re-wakes it — across deep push/pop. The enumerated set must equal the brute
        // set of assignments whose included nodes (succ[i] ≠ i) form exactly one cycle (empty valid).
        fun validSubcircuit(a: IntArray): Boolean {
            val n = a.size
            for (s in a) if (s < 0 || s >= n) return false
            val included = (0 until n).filter { a[it] != it }
            if (included.isEmpty()) return true
            for (i in included) if (a[a[i]] == a[i]) return false // points to an excluded node
            val start = included[0]
            var cur = start
            var count = 0
            do {
                cur = a[cur]
                count++
                if (count > n) return false
            } while (cur != start)
            return count == included.size
        }
        for (n in 3..4) {
            val brute = HashSet<List<Int>>()
            val acc = IntArray(n)
            fun rec(p: Int) {
                if (p == n) {
                    if (validSubcircuit(acc)) brute.add(acc.toList())
                    return
                }
                for (v in 0 until n) {
                    acc[p] = v
                    rec(p + 1)
                }
            }
            rec(0)
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, n - 1) },
                factors = arrayOf<Factor>(Subcircuit(succ = IntArray(n) { it })),
            )
            val found = BacktrackSolver(problem).enumerate(BacktrackParams(randomSeed = 1L)).take(100_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "subcircuit n=$n: enumerated set must equal brute force")
        }
    }
}
