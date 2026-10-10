package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InversePropagatorTest {

    /**
     * Soundness gate for the pair-local conflict explanations. Under the full CDCL
     * backtracker (VSIDS + clause forgetting, so the sharpened pair antecedents are exercised
     * by learning) enumeration must equal the brute-force mutual-inverse solution set. An
     * unsound reason — citing too small a pair — would drop a feasible assignment.
     */
    @Test
    fun `inverse deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x1DE5)
        repeat(300) { iter ->
            val n = 4
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2 * n,
                intDomains = Array(2 * n) { IntDomain(0, n - 1L) },
                factors = arrayOf<Factor>(Inverse(f = IntArray(n) { it }, g = IntArray(n) { n + it })),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "inverse#$iter") { state ->
                (0 until 5).all { state.excludeIntValue(rng.nextInt(2 * n), rng.nextInt(n).toLong()) }
            }
        }
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
        val r = BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L))
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(0, sat.assignment.ints[5], "g[2] (= var 5) must equal 0")
    }

    @Test
    fun `a removed value cascades to the mirrored side`() {
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
