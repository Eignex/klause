package com.eignex.klause.solver.lp

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.PropagationSession
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #655 (Tranche A): the layer-expanded DFA flow hull of [Regular]. The flow polytope is integral, so
 * the LP optimum of a linear objective over the sequence must **equal** the true optimum over the
 * automaton's accepting strings — checked against brute force. (Equality ⇒ both sound — never below
 * the true optimum — and tight.)
 */
class RegularFlowHullTest {

    private val eps = 1e-9

    /** Run the 1-based DFA on [seq]; true iff it ends in an accepting state (0 transition = reject). */
    private fun accepts(seq: IntArray, alphabet: Int, trans: IntArray, q0: Int, acc: Set<Int>): Boolean {
        var q = q0
        for (sym in seq) {
            if (sym < 1 || sym > alphabet) return false
            q = trans[(q - 1) * alphabet + (sym - 1)]
            if (q == 0) return false
        }
        return q in acc
    }

    @Test
    fun `flow hull gives the exact optimum on an even-count DFA`() {
        // 2 states, alphabet {1,2}: state tracks parity of symbol 2; accept even count. δ table row-major.
        val trans = intArrayOf(1, 2, 2, 1) // δ(1,1)=1 δ(1,2)=2 δ(2,1)=2 δ(2,2)=1
        val p = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(1, 2) },
            factors = arrayOf<Factor>(Regular(intArrayOf(0, 1, 2), 2, 2, trans, q0 = 1, accepting = intArrayOf(1))),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1, 1, 1)) // minimize Σ seq
        val r = CpToLpRelaxation(p, obj, regularHull = true).build(PropagationSession(p))
        val sol = solveSparse(r.model)
        assertEquals(LpStatus.OPTIMAL, sol.status)
        // Cheapest accepted string is 1,1,1 (zero 2s, even), Σ = 3.
        assertEquals(3.0, sol.objectiveValue, eps)
    }

    @Test
    fun `randomized flow hull matches the brute-force optimum`() {
        val rng = Random(20260616)
        var checked = 0
        repeat(300) { _ ->
            val numStates = rng.nextInt(2, 4)
            val alphabet = rng.nextInt(2, 4)
            val len = rng.nextInt(2, 5)
            val trans = IntArray(numStates * alphabet) { rng.nextInt(0, numStates + 1) } // 0..numStates (0 = dead)
            val q0 = 1
            val accCount = rng.nextInt(1, numStates + 1)
            val acc = HashSet<Int>()
            while (acc.size < accCount) acc.add(rng.nextInt(1, numStates + 1))
            val c = LongArray(len) { rng.nextInt(-3, 4).toLong() }
            val p = Problem(
                numBoolVars = 0,
                numIntVars = len,
                intDomains = Array(len) { IntDomain(1, alphabet) },
                factors = arrayOf<Factor>(
                    Regular(IntArray(len) { it }, numStates, alphabet, trans, q0, acc.toIntArray()),
                ),
            )
            val obj = LinearObjective(intCoefficients = c)

            // Brute force: minimum objective over accepted strings in the domains.
            var brute: Long? = null
            val x = IntArray(len)
            fun rec(i: Int) {
                if (i == len) {
                    if (!accepts(x, alphabet, trans, q0, acc)) return
                    var o = 0L
                    for (k in 0 until len) o += c[k] * x[k]
                    if (brute == null || o < brute!!) brute = o
                    return
                }
                for (v in 1..alphabet) {
                    x[i] = v
                    rec(i + 1)
                }
            }
            rec(0)

            val r = CpToLpRelaxation(p, obj, regularHull = true).build(PropagationSession(p))
            val sol = solveSparse(r.model)
            val opt = brute ?: return@repeat // no accepting string: the hull is skipped (a relaxation may loosen)
            checked++
            assertEquals(LpStatus.OPTIMAL, sol.status, "accepted string exists but LP not optimal")
            // Integral flow polytope ⇒ the LP optimum equals the true optimum over accepted strings.
            assertEquals(
                opt.toDouble(),
                sol.objectiveValue,
                eps,
                "flow hull optimum ${sol.objectiveValue} != brute $opt",
            )
        }
        assertTrue(checked > 100, "only $checked feasible instances checked")
    }
}
