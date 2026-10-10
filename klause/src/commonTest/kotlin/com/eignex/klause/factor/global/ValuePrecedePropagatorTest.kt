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
import com.eignex.klause.propagation.holeReasonFor
import com.eignex.klause.propagation.reasonOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class ValuePrecedePropagatorTest {

    @Test
    fun `value precede deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x7A1E)
        repeat(300) { iter ->
            val n = 5
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = n,
                intDomains = Array(n) { IntDomain(0, 3) },
                factors = listOf(ValuePrecede(1L, 2L + rng.nextInt(2), IntArray(n) { it })),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "value-precede#$iter") { state ->
                (0 until 5).all {
                    val v = rng.nextInt(n)
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(4).toLong())
                        1 -> state.tightenIntMin(v, 1L + rng.nextInt(2))
                        else -> state.tightenIntMax(v, 1L + rng.nextInt(3))
                    }
                }
            }
        }
    }

    @Test
    fun `a t pruned before any s cites only the positions that lost s`() {
        // 1 must precede 2: x0 lost 1, so x1 cannot hold 2; x3's hole at 0 plays no part.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 3) },
            factors = arrayOf<Factor>(ValuePrecede(s = 1, t = 2, xs = IntArray(4) { it })),
        )
        val state = PropagationState(problem, Assumptions.None)
        state.undoLogging = true
        state.currentLevel = 1
        check(state.excludeIntValue(0, 1) && state.excludeIntValue(3, 0))
        state.currentFactor = 0

        check(state.factorAt(0).propagate(state, 0))

        val cited = state.reasonOf(state.holeReasonFor(1, 2))!!.map { lit ->
            val atom = Lit.variable(lit) - problem.numBoolVars
            Triple(state.atoms.intVar[atom], state.atoms.kind[atom], state.atoms.threshold[atom])
        }
        assertEquals(listOf(Triple(0, AtomKind.EQ, 1L)), cited)
    }
}
