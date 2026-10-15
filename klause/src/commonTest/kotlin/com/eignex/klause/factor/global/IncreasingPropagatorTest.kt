package com.eignex.klause.factor.global

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.AtomKind
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncreasingPropagatorTest {

    private fun chain(strict: Boolean, n: Int = 3, lo: Int = 0, hi: Int = 3) = Problem(
        numBoolVars = 0,
        numIntVars = n,
        intDomains = Array(n) { IntDomain(lo.toLong(), hi.toLong()) },
        factors = arrayOf<Factor>(Increasing(IntArray(n) { it }, strict = strict)),
    )

    @Test
    fun `strict chains should reject a descent across both signed word endpoints`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(Long.MAX_VALUE, Long.MAX_VALUE), IntDomain(Long.MIN_VALUE, Long.MIN_VALUE)),
            factors = arrayOf<Factor>(Increasing(intArrayOf(0, 1), strict = true)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.currentFactor = 0

        assertFalse(problem.propagators[0].propagate(state, 0))
    }

    @Test
    fun `forward and backward sweeps should tighten later mins and earlier maxes`() {
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
        assertEquals(3, state.intDomains[1].min)
        assertEquals(4, state.intDomains[2].min)
        assertEquals(4, state.intDomains[1].max)
        assertEquals(3, state.intDomains[0].max)
    }

    @Test
    fun `increasing deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x1C5)
        repeat(100) { iter ->
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
    fun `a chain bound should cite only its neighbour's bound on the same side`() {
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
