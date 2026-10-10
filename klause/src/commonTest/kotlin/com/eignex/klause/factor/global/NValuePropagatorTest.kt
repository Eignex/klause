package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.FactorPropagationOracle
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
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
    fun `a kernel count cites only the bounds that keep its windows apart`() {
        // x0 <= 1 and x1 >= 3 need two values between them; x2's hole at 2 plays no part.
        val problem = nvalueProblem(Array(3) { IntDomain(0, 4) }, IntDomain(0, 3), NValue.Mode.AtMost)
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMax(0, 1) && state.tightenIntMin(1, 3) && state.excludeIntValue(2, 2))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMinAntecedents[3])!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(setOf(Triple(0, AtomKind.LE, 1L), Triple(1, AtomKind.GE, 3L)), cited.toSet())
    }

    @Test
    fun `nvalue deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x57A2)
        for (mode in NValue.Mode.entries) {
            repeat(100) { iter ->
                val k = 4
                val problem = nvalueProblem(Array(k) { IntDomain(0, 3) }, IntDomain(0, k.toLong()), mode)
                PropagationReasonOracle.assertReasonsImply(problem, "nvalue-$mode#$iter") { state ->
                    (0 until 5).all {
                        val v = rng.nextInt(k + 1)
                        when (rng.nextInt(3)) {
                            0 -> state.excludeIntValue(v, rng.nextInt(4).toLong())
                            1 -> state.tightenIntMin(v, 1L + rng.nextInt(2))
                            else -> state.tightenIntMax(v, 1L + rng.nextInt(3))
                        }
                    }
                }
            }
        }
    }

}
