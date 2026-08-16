package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.FactorPropagationOracle
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NValuePropagatorTest {

    private fun nvalueProblem(xsDomains: Array<IntDomain>, nDomain: IntDomain, mode: NValue.Mode): Problem {
        val k = xsDomains.size
        return Problem(
            numBoolVars = 0,
            numIntVars = k + 1,
            intDomains = xsDomains + nDomain,
            factors = arrayOf<Factor>(NValue(n = k, xs = IntArray(k) { it }, mode = mode)),
        )
    }

    @Test
    fun `nvalue matching and kernel filtering never over-prune`() {
        // Brute-force oracle across all three modes: matching upper bound (atLeast/eq), kernel
        // lower bound and kernel xs-squeeze (atMost/eq) must hold on every solution. Instances stay
        // under the BruteForceSolver 2^18 cap.
        val rng = Random(0x57A1)
        for (mode in NValue.Mode.entries) {
            repeat(300) { iter ->
                val k = 2 + rng.nextInt(3) // 2..4 counted vars
                val maxVal = if (k >= 4) 3 else 4
                val xsDomains = Array(k) {
                    val a = rng.nextInt(maxVal + 1)
                    val b = rng.nextInt(maxVal + 1)
                    IntDomain(minOf(a, b).toLong(), maxOf(a, b).toLong())
                }
                val a = rng.nextInt(k + 1)
                val b = rng.nextInt(k + 1)
                val nDomain = IntDomain(minOf(a, b).toLong(), maxOf(a, b).toLong())
                FactorPropagationOracle.assertSound(nvalueProblem(xsDomains, nDomain, mode), "nvalue-$mode#$iter")
            }
        }
    }

    @Test
    fun `atleast nvalues forces a variable pinned by the maximum matching`() {
        // x0 ∈ {0}, x1 ∈ {0,1}, with at-least 2 distinct values required. x1 = 0 would leave only
        // one distinct value, so the maximum matching forces x1 = 1. Layout: x0=0, x1=1, n=2.
        val problem = nvalueProblem(
            xsDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 1)),
            nDomain = IntDomain(2, 2),
            mode = NValue.Mode.AtLeast,
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentFactor = 0
        assertTrue(problem.propagators[0].propagate(state, 0))
        assertEquals(1, state.intDomains[1].min, "x1 must be pinned to 1 by the maximum-matching GAC")
        assertEquals(1, state.intDomains[1].max, "x1 must be pinned to 1 by the maximum-matching GAC")
    }

    @Test
    fun `atleast nvalues reaches arc consistency`() {
        // Stronger than assertSound: when the count is pinned to the maximum matching size, the
        // Régin value-pruning must remove exactly the values with no support — full GAC.
        val rng = Random(0xA71EA57)
        repeat(300) { iter ->
            val k = 2 + rng.nextInt(2) // 2..3 counted vars
            val maxVal = if (k >= 3) 4 else 5
            val xsDomains = Array(k) {
                val a = rng.nextInt(maxVal + 1)
                val b = rng.nextInt(maxVal + 1)
                IntDomain(minOf(a, b).toLong(), maxOf(a, b).toLong())
            }
            // Pin the count high to provoke the matching-saturated pruning regime.
            val target = 1 + rng.nextInt(k)
            FactorPropagationOracle.assertGac(
                nvalueProblem(xsDomains, IntDomain(target.toLong(), target.toLong()), NValue.Mode.AtLeast),
                "atleast-gac#$iter",
            )
        }
    }

    @Test
    fun `atmost kernel squeezes a variable into its window`() {
        // Two vars pinned to disjoint values 0 and 2 form a 2-window kernel; with n ≤ 2 the third
        // var (∈ [0,2]) may not take a value outside the kernel windows — but since both windows are
        // singletons here the only freedom is forced. Soundness is the assertion; the oracle checks
        // the propagated bounds against the 3-solution ground truth.
        val problem = nvalueProblem(
            xsDomains = arrayOf(IntDomain(0, 0), IntDomain(2, 2), IntDomain(0, 2)),
            nDomain = IntDomain(0, 2),
            mode = NValue.Mode.AtMost,
        )
        FactorPropagationOracle.assertSound(problem, "atmost-kernel")
    }

    @Test
    fun `nvalue counts distinct values exactly`() {
        // xs of size 4, n must equal distinct values. Pin some duplicates.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(1, 1),
                IntDomain(1, 1),
                IntDomain(2, 2),
                IntDomain(3, 3),
                IntDomain(0, 5),
            ),
            factors = arrayOf<Factor>(NValue(n = 4, xs = intArrayOf(0, 1, 2, 3), mode = NValue.Mode.Eq)),
        )
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(3, sat.assignment.ints[4], "distinct = {1, 2, 3} = 3")
    }

    @Test
    fun `atleast_nvalues enforces n le distinct`() {
        // 4 xs each ∈ [0, 3]. n = 4 forces all-different. 4 distinct values within [0,3].
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(0, 3),
                IntDomain(0, 3),
                IntDomain(0, 3),
                IntDomain(0, 3),
                IntDomain(4, 4),
            ),
            factors = arrayOf<Factor>(NValue(n = 4, xs = intArrayOf(0, 1, 2, 3), mode = NValue.Mode.AtLeast)),
        )
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        // Distinct count must be ≥ 4 → all-different.
        val vs = listOf(sat.assignment.ints[0], sat.assignment.ints[1], sat.assignment.ints[2], sat.assignment.ints[3])
        assertEquals(vs.distinct().size, vs.size, "atleast_nvalues 4 → all-different; got $vs")
    }

    @Test
    fun `atmost_nvalues caps distinct count`() {
        // 4 xs ∈ [1, 5], n = 2: at most 2 distinct values used.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(1, 5),
                IntDomain(1, 5),
                IntDomain(1, 5),
                IntDomain(1, 5),
                IntDomain(2, 2),
            ),
            factors = arrayOf<Factor>(NValue(n = 4, xs = intArrayOf(0, 1, 2, 3), mode = NValue.Mode.AtMost)),
        )
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        val vs = listOf(sat.assignment.ints[0], sat.assignment.ints[1], sat.assignment.ints[2], sat.assignment.ints[3])
        assertTrue(vs.distinct().size <= 2, "atmost_nvalues 2 violated: got $vs")
    }

    @Test
    fun `atmost independent-set lower bound forces Unsat`() {
        // Three pairwise domain-disjoint vars must take 3 distinct values, but AtMost caps the
        // distinct count at n = 2 ⟹ UNSAT. Exercises the greedy independent-set lower bound.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(0, 1),
                IntDomain(2, 3),
                IntDomain(4, 5),
                IntDomain(2, 2),
            ),
            factors = arrayOf<Factor>(NValue(n = 3, xs = intArrayOf(0, 1, 2), mode = NValue.Mode.AtMost)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `atmost independent-set lower bound is hole-aware`() {
        // Disjointness via interior holes: {0,2}, {1}, {3,5} are pairwise disjoint ⟹ 3 distinct
        // required, but n = 2 caps it ⟹ UNSAT. The conflict reason must cite holes soundly.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(0, 2).excludeValue(1),
                IntDomain(1, 1),
                IntDomain(3, 5).excludeValue(4),
                IntDomain(2, 2),
            ),
            factors = arrayOf<Factor>(NValue(n = 3, xs = intArrayOf(0, 1, 2), mode = NValue.Mode.AtMost)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `backtrack enumeration over nvalue equals brute force for every mode`() {
        // Soundness gate for the delta-gated propagator: enumerating under the CDCL backtracker fires
        // propagate repeatedly on one PropagationState — the delta fast path skips no-op re-fires, a
        // domain change re-wakes it — across deep push/pop. At a complete assignment min == max
        // distinct, so n is forced exactly; the enumerated (xs..., n) set must equal the true
        // mode-relation set. An unsound skip would drop or admit an assignment.
        val xsCount = 3
        val xsHi = 3
        val nHi = 3
        for (mode in NValue.Mode.entries) {
            fun relates(nv: Int, distinct: Int): Boolean = when (mode) {
                NValue.Mode.Eq -> nv == distinct
                NValue.Mode.AtLeast -> nv <= distinct
                NValue.Mode.AtMost -> nv >= distinct
            }
            val brute = HashSet<List<Int>>()
            val acc = IntArray(xsCount)
            fun rec(p: Int) {
                if (p == xsCount) {
                    val distinct = acc.toHashSet().size
                    for (nv in 1..nHi) if (relates(nv, distinct)) brute.add(acc.toList() + nv)
                    return
                }
                for (v in 1..xsHi) {
                    acc[p] = v
                    rec(p + 1)
                }
            }
            rec(0)
            // var ids: xs = 0..xsCount-1, n = xsCount.
            val doms = Array(
                xsCount + 1,
            ) { if (it < xsCount) IntDomain(1, xsHi.toLong()) else IntDomain(1, nHi.toLong()) }
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = xsCount + 1,
                intDomains = doms,
                factors = arrayOf<Factor>(NValue(n = xsCount, xs = IntArray(xsCount) { it }, mode = mode)),
            )
            val found = BacktrackSolver(problem.bake()).enumerate(BacktrackParams(randomSeed = 1L)).take(100_000)
                .map { s -> s.ints.map { it.toInt() } }.toHashSet()
            assertEquals(brute, found, "mode=$mode: enumerated (xs, n) set must equal brute force")
        }
    }
}
