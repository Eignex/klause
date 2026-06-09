package com.eignex.klause.logicng

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.factor.Table
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end validation of the bit-blasted [Circuit] / [Subcircuit] / [Cumulative] encodings
 * through a real SAT solver (LogicNG/MiniSat). These factors synthesise encoding-internal
 * position vars that the in-tree brute-force oracle can't complete, so correctness — and in
 * particular the MTZ single-cycle property — is checked here.
 */
class GapFactorLogicNgTest {

    private fun solve(problem: Problem): SolveResult = LogicNGSolver(problem).solve(LogicNGParams(randomSeed = 0L))

    @Test
    fun `circuit is satisfiable and forms a single hamiltonian cycle`() {
        val n = 4
        val problem = Problem(
            0,
            n,
            Array(n) { IntDomain(0, n - 1) },
            arrayOf<Factor>(
                Circuit(succ = IntArray(n) { it }),
            ),
        )
        val result = solve(problem)
        val sat = assertIs<SolveResult.Sat>(result, "circuit should be satisfiable")
        val succ = sat.assignment.ints
        // Following succ from node 0 must visit all n nodes before returning to 0.
        val visited = HashSet<Int>()
        var node = 0
        repeat(n) {
            assertTrue(node in 0 until n, "succ out of range: $node")
            assertTrue(visited.add(node), "node $node revisited before closing — not a single cycle")
            node = succ[node]
        }
        assertTrue(node == 0, "cycle did not close back to node 0")
        assertTrue(visited.size == n, "circuit did not visit all nodes: $visited")
    }

    @Test
    fun `circuit rejects forced disjoint subcycles`() {
        // Pin succ = [1,0,3,2]: a permutation with no self-loops but two 2-cycles. The MTZ
        // position reasoning must rule it out.
        val n = 4
        val problem = Problem(
            0,
            n,
            Array(n) { IntDomain(0, n - 1) },
            arrayOf<Factor>(
                Circuit(succ = IntArray(n) { it }),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.EQ, 3),
                Linear(intArrayOf(1), intArrayOf(3), LinearOp.EQ, 2),
            ),
        )
        assertIs<SolveResult.Unsat>(solve(problem), "two disjoint 2-cycles must be UNSAT for circuit")
    }

    @Test
    fun `subcircuit allows an all-excluded assignment`() {
        val n = 3
        val problem = Problem(
            0,
            n,
            Array(n) { IntDomain(0, n - 1) },
            arrayOf<Factor>(
                Subcircuit(succ = IntArray(n) { it }),
                // Force every node to self-loop (excluded): succ[i] = i. Valid empty subcircuit.
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.EQ, 2),
            ),
        )
        assertIs<SolveResult.Sat>(solve(problem), "all-excluded subcircuit should be satisfiable")
    }

    @Test
    fun `subcircuit accepts a single included 2-cycle with an excluded self-loop`() {
        // n=3, force a 2-cycle on {0,1} and a self-loop on {2}: 0→1→0, 2 excluded. This is
        // a valid subcircuit (single cycle among included nodes).
        val n = 3
        val problem = Problem(
            0,
            n,
            Array(n) { IntDomain(0, n - 1) },
            arrayOf<Factor>(
                Subcircuit(succ = IntArray(n) { it }),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.EQ, 2),
            ),
        )
        assertIs<SolveResult.Sat>(solve(problem), "single included 2-cycle with exclusion should be SAT")
    }

    private fun eq(v: Int, value: Int): Linear = Linear(intArrayOf(1), intArrayOf(v), LinearOp.EQ, value)

    @Test
    fun `regular rejects a string the dfa does not accept`() {
        // Even number of symbol-2; a single 2 ends in non-accepting state 2.
        val problem = Problem(
            0,
            1,
            arrayOf(IntDomain(1, 2)),
            arrayOf<Factor>(
                Regular(
                    seq = intArrayOf(0),
                    numStates = 2,
                    alphabetSize = 2,
                    transitions = intArrayOf(1, 2, 2, 1),
                    q0 = 1,
                    accepting = intArrayOf(1),
                ),
                eq(0, 2),
            ),
        )
        assertIs<SolveResult.Unsat>(solve(problem))
    }

    @Test
    fun `regular accepts a valid string`() {
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            arrayOf<Factor>(
                Regular(
                    seq = intArrayOf(0, 1),
                    numStates = 2,
                    alphabetSize = 2,
                    transitions = intArrayOf(1, 2, 2, 1),
                    q0 = 1,
                    accepting = intArrayOf(1),
                ),
                eq(0, 2),
                eq(1, 2), // two 2s ⇒ back to accepting state 1
            ),
        )
        assertIs<SolveResult.Sat>(solve(problem))
    }

    @Test
    fun `inverse rejects inconsistent channels`() {
        // f = [1,0,2]; the consistent inverse is g = [1,0,2]. Force g = identity ⇒ UNSAT.
        val problem = Problem(
            0,
            6,
            Array(6) { IntDomain(0, 2) },
            arrayOf<Factor>(
                Inverse(f = intArrayOf(0, 1, 2), g = intArrayOf(3, 4, 5)),
                eq(0, 1),
                eq(1, 0),
                eq(2, 2),
                eq(3, 0),
                eq(4, 1),
                eq(5, 2),
            ),
        )
        assertIs<SolveResult.Unsat>(solve(problem))
    }

    @Test
    fun `lex_less strict rejects equal vectors`() {
        val problem = Problem(
            0,
            4,
            Array(4) { IntDomain(0, 3) },
            arrayOf<Factor>(
                LexLess(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3), strict = true),
                eq(0, 1),
                eq(1, 2),
                eq(2, 1),
                eq(3, 2), // xs == ys
            ),
        )
        assertIs<SolveResult.Unsat>(solve(problem))
    }

    @Test
    fun `element rejects a value mismatch`() {
        // idx = 1 selects arr[0] = 5, but result forced to 7 ⇒ UNSAT.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(1, 3), IntDomain(5, 9)),
            arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = intArrayOf(5, 7, 9), arrIsVars = false, indexOffset = 1),
                eq(0, 1),
                eq(1, 7),
            ),
        )
        assertIs<SolveResult.Unsat>(solve(problem))
    }

    @Test
    fun `table rejects a non-listed tuple`() {
        val problem = Problem(
            0,
            2,
            Array(2) { IntDomain(0, 2) },
            arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = intArrayOf(0, 0, 1, 2, 2, 1)),
                eq(0, 0),
                eq(1, 1), // (0,1) is not a listed tuple
            ),
        )
        assertIs<SolveResult.Unsat>(solve(problem))
    }

    @Test
    fun `cumulative rejects an over-capacity forced overlap`() {
        // Two unit-window tasks both forced to start at 0, each demanding 1, capacity 1 →
        // overload at t=0. UNSAT.
        val problem = Problem(
            0,
            2,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            arrayOf<Factor>(
                Cumulative(
                    starts = intArrayOf(0, 1),
                    durations = intArrayOf(2, 2),
                    resources = intArrayOf(1, 1),
                    capacity = 1,
                ),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 0),
            ),
        )
        assertIs<SolveResult.Unsat>(solve(problem), "over-capacity overlap must be UNSAT")
    }
}
