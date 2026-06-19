package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.factor.circuit.Circuit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Brute-force equivalence oracle for [Circuit] propagation soundness. For small `n` with
 * randomly restricted (interval) successor domains — the regime the `is` MiniZinc model
 * creates via `succ[null]=entry` and per-node domain restrictions — the complete
 * [BacktrackSolver] must agree with full enumeration on SAT/UNSAT. A klause `Unsat` where a
 * Hamiltonian completion exists is an over-pruning bug (false UNSAT).
 */
class CircuitBruteTest {

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
}
