package com.eignex.klause.factor.global

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.SolveResult
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InversePropagatorTest {

    @Test
    fun `inverse should preserve channel values beyond the largest int offset`() {
        val offset = Int.MAX_VALUE.toLong()
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(
                IntDomain(offset + 1, offset + 1), IntDomain(offset, offset),
                IntDomain(offset, offset + 1), IntDomain(offset, offset + 1),
            ),
            factors = arrayOf<Factor>(
                Inverse(intArrayOf(0, 1), intArrayOf(2, 3), Int.MAX_VALUE, Int.MAX_VALUE),
            ),
        )

        val state = PropagationState(problem, Assumptions.None)
        state.currentFactor = 0

        assertTrue(state.factorAt(0).propagate(state, 0))

        assertEquals(offset + 1, state.intDomains[2].min)
        assertEquals(offset + 1, state.intDomains[2].max)
        assertEquals(offset, state.intDomains[3].min)
        assertEquals(offset, state.intDomains[3].max)
    }

    @Test
    fun `inverse deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x1DE5)
        repeat(60) { iter ->
            val n = 3
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2 * n,
                intDomains = Array(2 * n) { IntDomain(0, n - 1L) },
                factors = arrayOf<Factor>(Inverse(f = IntArray(n) { it }, g = IntArray(n) { n + it })),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "inverse#$iter") { state ->
                (0 until 3).all { state.excludeIntValue(rng.nextInt(2 * n), rng.nextInt(n).toLong()) }
            }
        }
    }

    @Test
    fun `singleton on one side should force the other`() {
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
        assertEquals(0, sat.assignment.ints[5])
    }

    @Test
    fun `a removed value should cascade to the mirrored side`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(1, 1), IntDomain(0, 1)),
            factors = arrayOf<Factor>(Inverse(f = intArrayOf(0, 1), g = intArrayOf(2, 3))),
        )
        val impl = assertIs<PropagationResult.Implied>(problem.propagate(Assumptions.None))
        assertEquals(1, impl.intValueOrNull(0))
        assertEquals(0, impl.intValueOrNull(3))
    }
}
