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

    @Test
    fun `increasing deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x1C5)
        repeat(300) { iter ->
            val problem = chain(strict = rng.nextBoolean(), n = 4, hi = 5)
            PropagationReasonOracle.assertReasonsImply(problem, "increasing#$iter") { state ->
                (0 until 4).all {
                    val v = rng.nextInt(4)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(6).toLong())
                        1 -> state.tightenIntMin(v, rng.nextInt(4).toLong())
                        else -> state.tightenIntMax(v, 2L + rng.nextInt(4))
                    }
                }
            }
        }
    }

    @Test
    fun `a chain bound cites only its neighbour's bound on the same side`() {
        // x0 <= x1: raising x0 to 2 raises x1 to 2, citing x0 >= 2 alone; x0's upper bound plays no part.
        val problem = chain(strict = false, n = 2, hi = 5)
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(0, 2) && state.tightenIntMax(0, 4))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.intMinAntecedents[1])!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(listOf(Triple(0, AtomKind.GE, 2L)), cited)
    }
}
