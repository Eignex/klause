package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.FactorPropagationOracle
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IncreasingPropagatorTest {

    private fun chain(strict: Boolean, n: Int = 3, lo: Int = 0, hi: Int = 3) = Problem(
        numBoolVars = 0,
        numIntVars = n,
        intDomains = Array(n) { IntDomain(lo.toLong(), hi.toLong()) },
        factors = arrayOf<Factor>(Increasing(IntArray(n) { it }, strict = strict)),
    )

    @Test
    fun `propagation is sound and GAC in both strictness modes`() {
        val cases = listOf(
            "increasing" to chain(strict = false),
            "strictly_increasing" to chain(strict = true, hi = 4),
        )
        for ((label, problem) in cases) {
            FactorPropagationOracle.assertSound(problem, label)
            FactorPropagationOracle.assertGac(problem, label)
        }
    }

    @Test
    fun `every enumerated solution is non-decreasing`() {
        val problem = chain(strict = false)
        BacktrackSolver(problem.bake()).enumerate(BacktrackParams(randomSeed = 0L)).take(50).forEach { s ->
            assertTrue((0 until 2).all { s.ints[it] <= s.ints[it + 1] }, "not non-decreasing: ${s.ints.toList()}")
        }
    }

    @Test
    fun `strictly increasing on an equal pinned pair is Unsat`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = Array(2) { IntDomain(1, 1) },
            factors = arrayOf<Factor>(Increasing(intArrayOf(0, 1), strict = true)),
        )
        assertIs<SolveResult.Unsat>(BacktrackSolver(problem.bake()).solve(BacktrackParams(randomSeed = 0L)))
    }

    @Test
    fun `forward sweep raises later mins and backward sweep lowers earlier maxes`() {
        // x0 ∈ [2,9], x1 ∈ [0,9], x2 ∈ [0,5], strictly increasing.
        // Forward: x1.min ≥ x0.min+1 = 3, x2.min ≥ x1.min+1 = 4.
        // Backward: x1.max ≤ x2.max−1 = 4, x0.max ≤ x1.max−1 = 3.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(2, 9), IntDomain(0, 9), IntDomain(0, 5)),
            factors = arrayOf<Factor>(Increasing(intArrayOf(0, 1, 2), strict = true)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentFactor = 0
        assertTrue(problem.propagators[0].propagate(state, 0))
        assertEquals(3, state.intDomains[1].min, "x1.min raised to x0.min+1")
        assertEquals(4, state.intDomains[2].min, "x2.min raised along the chain")
        assertEquals(4, state.intDomains[1].max, "x1.max lowered to x2.max-1")
        assertEquals(3, state.intDomains[0].max, "x0.max lowered along the chain")
    }
}
