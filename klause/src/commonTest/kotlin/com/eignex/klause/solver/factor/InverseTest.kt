package com.eignex.klause.solver.factor

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.propagation.PropagationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InverseTest {

    /**
     * Soundness gate for the pair-local conflict explanations. Under the full CDCL
     * backtracker (VSIDS + clause forgetting, so the sharpened pair antecedents are exercised
     * by learning) enumeration must equal the brute-force mutual-inverse solution set. An
     * unsound reason — citing too small a pair — would drop a feasible assignment.
     */
    @Test
    fun `backtrack learning enumerates exactly the brute-force solution set`() {
        // var ids: f = 0..n-1, g = n..2n-1; per-var [min,max] over 0..n-1 (0-based offsets).
        val instances = listOf(
            Triple(3, listOf(0 to 2, 0 to 2, 0 to 2), listOf(0 to 2, 0 to 2, 0 to 2)),
            Triple(3, listOf(1 to 1, 0 to 2, 0 to 2), listOf(0 to 2, 0 to 2, 0 to 2)), // f0 pinned
            Triple(3, listOf(0 to 1, 0 to 1, 0 to 2), listOf(0 to 2, 0 to 2, 0 to 2)), // tight f
            Triple(4, listOf(0 to 3, 0 to 3, 0 to 3, 0 to 3), listOf(0 to 3, 0 to 3, 0 to 3, 0 to 3)),
        )
        for ((idx, inst) in instances.withIndex()) {
            val (n, fr, gr) = inst
            val k = 2 * n
            val brute = HashSet<List<Int>>()
            val acc = IntArray(k)
            fun ok(): Boolean {
                for (i in 0 until n) { // f[i]=j ⇒ valid index and g[j]=i
                    val j = acc[i]
                    if (j !in 0 until n || acc[n + j] != i) return false
                }
                for (i in 0 until n) { // g[i]=j ⇒ valid index and f[j]=i
                    val j = acc[n + i]
                    if (j !in 0 until n || acc[j] != i) return false
                }
                return true
            }
            fun rec(p: Int) {
                if (p == k) {
                    if (ok()) brute.add(acc.toList())
                    return
                }
                val range = if (p < n) fr[p] else gr[p - n]
                for (v in range.first..range.second) {
                    acc[p] = v
                    rec(p + 1)
                }
            }
            rec(0)

            val doms = Array(k) {
                if (it < n) IntDomain(fr[it].first, fr[it].second) else IntDomain(gr[it - n].first, gr[it - n].second)
            }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = k,
                intDomains = doms,
                factors = arrayOf<Factor>(Inverse(f = IntArray(n) { it }, g = IntArray(n) { n + it })),
            )
            val params = BacktrackParams(randomSeed = 1L, variableSelector = Vsids(), maxLearnedClauses = 1_000)
            val found = BacktrackSolver(problem).enumerate(params).take(100_000)
                .map { it.ints.toList() }.toHashSet()
            assertEquals(brute, found, "instance #$idx: backtrack solution set must equal brute force")
        }
    }

    @Test
    fun `0-based inverse pair`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 2) },
            factors = arrayOf<Factor>(Inverse(f = intArrayOf(0, 1, 2), g = intArrayOf(3, 4, 5))),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val f = listOf(sat.assignment.ints[0], sat.assignment.ints[1], sat.assignment.ints[2])
        val g = listOf(sat.assignment.ints[3], sat.assignment.ints[4], sat.assignment.ints[5])
        // For each i: g[f[i]] = i.
        for (i in 0..2) assertEquals(i, g[f[i]], "g[f[$i]]=g[${f[i]}]=${g[f[i]]} ≠ $i")
    }

    @Test
    fun `inverse with 1-based offsets`() {
        // f, g both 1-indexed, domain [1..3].
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(1, 3) },
            factors = arrayOf<Factor>(
                Inverse(
                    f = intArrayOf(0, 1, 2),
                    g = intArrayOf(3, 4, 5),
                    fOffset = 1,
                    gOffset = 1,
                ),
            ),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val f = listOf(sat.assignment.ints[0], sat.assignment.ints[1], sat.assignment.ints[2])
        val g = listOf(sat.assignment.ints[3], sat.assignment.ints[4], sat.assignment.ints[5])
        // For each i (1-based): g[f[i] - 1] = i.
        for (i in 1..3) assertEquals(i, g[f[i - 1] - 1], "g[f[$i]]=${g[f[i - 1] - 1]} ≠ $i")
    }

    @Test
    fun `singleton on one side forces the other`() {
        // f[0] = 2 pinned ⇒ g[2] = 0 forced.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(2, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
            ),
            factors = arrayOf<Factor>(Inverse(f = intArrayOf(0, 1, 2), g = intArrayOf(3, 4, 5))),
        )
        val r = BacktrackSolver(problem).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(0, sat.assignment.ints[5], "g[2] (= var 5) must equal 0")
    }

    @Test
    fun `value removal and its cascade still fire after incremental rewrite`() {
        // n=2, 0-based. g[0] (var 2) pinned to 1, so g[0] ≠ 0 ⟹ remove 0 from f[0] (f[0]=1),
        // which in turn forces g[1] (var 3) = 0. Completeness guard for the incremental
        // row/column value-removal sweep.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(1, 1), IntDomain(0, 1)),
            factors = arrayOf<Factor>(Inverse(f = intArrayOf(0, 1), g = intArrayOf(2, 3))),
        )
        val impl = assertIs<PropagationResult.Implied>(problem.propagate(Assumptions.None))
        assertEquals(1, impl.intValueOrNull(0), "f[0] must lose 0 (g[0]≠0) and pin to 1")
        assertEquals(0, impl.intValueOrNull(3), "cascade: f[0]=1 ⟹ g[1]=0")
    }
}
