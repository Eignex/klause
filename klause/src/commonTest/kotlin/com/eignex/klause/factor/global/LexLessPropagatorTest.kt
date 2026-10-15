package com.eignex.klause.factor.global

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.factorAt
import com.eignex.klause.propagation.propagate
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LexLessPropagatorTest {

    @Test
    fun `empty vector comparisons should follow lexicographic length ordering`() {
        for (strict in listOf(false, true)) {
            for ((xs, ys) in listOf(
                intArrayOf() to intArrayOf(),
                intArrayOf() to intArrayOf(0),
                intArrayOf(0) to intArrayOf(),
            )) {
                val problem = Problem(
                    numBoolVars = 0,
                    numIntVars = 1,
                    intDomains = arrayOf(IntDomain(0, 1)),
                    factors = arrayOf<Factor>(LexLess(xs, ys, strict)),
                )
                val state = PropagationState(problem, Assumptions.None)
                state.currentFactor = 0
                val expected = xs.size < ys.size || (xs.size == ys.size && !strict)

                assertEquals(expected, problem.propagators[0].propagate(state, 0))
                if (!expected) assertNull(problem.propagators[0].conflictReason(state, 0))
            }
        }
    }

    @Test
    fun `a lex conflict should cite only the prefix and the pair that decides it`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1, 2), intArrayOf(3, 4, 5), strict = true)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.tightenIntMin(0, 1) && state.tightenIntMax(0, 1))
        check(state.tightenIntMin(3, 1) && state.tightenIntMax(3, 1))
        check(state.tightenIntMin(1, 2) && state.tightenIntMax(4, 1))
        check(state.tightenIntMax(2, 2) && state.tightenIntMin(5, 1))
        state.currentFactor = 0

        check(!state.factorAt(0).propagate(state, 0))

        val cited = state.factorAt(0).conflictReason(state, 0)!!
            .map { state.atoms.intVar[Lit.variable(it) - problem.numBoolVars] }.toSet()
        assertEquals(setOf(0, 1, 3, 4), cited)
    }

    @Test
    fun `strict lex should tighten the last deciding position strictly`() {
        // A non-strict step would only reach x1 <= y1.max = 2.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(0, 3), IntDomain(0, 0), IntDomain(0, 2)),
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = true)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentFactor = 0
        assertTrue(problem.propagators[0].propagate(state, 0))
        assertEquals(1, state.intDomains[1].max)
        assertEquals(1, state.intDomains[3].min)
    }

    @Test
    fun `lesseq should force a strict head step when the tail is pinned greater`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(2, 2), IntDomain(0, 2), IntDomain(0, 0)),
            factors = arrayOf<Factor>(LexLess(intArrayOf(0, 1), intArrayOf(2, 3), strict = false)),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentFactor = 0
        assertTrue(problem.propagators[0].propagate(state, 0))
        assertEquals(1, state.intDomains[0].max)
        assertEquals(1, state.intDomains[2].min)
    }

    @Test
    fun `lex less deductions should be implied by their reasons under carved holes`() {
        val rng = Random(0x1E70)
        repeat(300) { iter ->
            val n = 3
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 2 * n,
                intDomains = Array(2 * n) { IntDomain(0, 2) },
                factors = arrayOf<Factor>(
                    LexLess(IntArray(n) { it }, IntArray(n) { n + it }, strict = rng.nextBoolean()),
                ),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "lex#$iter") { state ->
                (0 until 5).all {
                    val v = rng.nextInt(2 * n)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(3).toLong())
                        1 -> state.tightenIntMin(v, 1L + rng.nextInt(2))
                        else -> state.tightenIntMax(v, rng.nextInt(2).toLong())
                    }
                }
            }
        }
    }
}
