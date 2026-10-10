package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.mark
import com.eignex.klause.propagation.undoTo
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.parseBigInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WideReifiedLinearPropagatorTest {

    // 2^64 — one past the signed 64-bit range, so it can only live in the wide coefficient lane.
    private val w = parseBigInt("18446744073709551616")

    // bool 0 = aux; int 0 = x, int 1 = y.
    private fun problem(factor: Factor, xHi: Long = 5, yHi: Long = 5) = Problem(
        numBoolVars = 1,
        numIntVars = 2,
        intDomains = arrayOf(IntDomain(0, xHi), IntDomain(0, yHi)),
        factors = arrayOf(factor),
    )

    @Test
    fun `incremental wide reification follows interior target membership through rollback`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            for (coefficient in listOf(w, -w)) {
                val problem = problem(ReifiedLinear(0, intArrayOf(0), arrayOf(coefficient), op,
                    coefficient * bigIntOf(2L)), xHi = 4L, yHi = 0L)
                val state = PropagationState(problem, Assumptions.None)
                state.undoLogging = true
                assertNull(state.runToFixpoint(allFactors = true))
                val root = state.mark()
                state.currentLevel = 1
                assertTrue(state.excludeIntValue(0, 1L))
                assertNull(state.runToFixpoint(allFactors = false))
                assertNull(state.boolValues[0])

                assertTrue(state.excludeIntValue(0, 2L))
                assertNull(state.runToFixpoint(allFactors = false))

                assertEquals(op == LinearOp.NE, state.boolValues[0])
                assertEquals(0L, state.intDomains[0].min)
                assertEquals(4L, state.intDomains[0].max)
                assertEquals(listOf(Lit.make(state.atomVarEq(0, 2L), true)), state.boolAntecedents[0]?.toList())
                state.undoTo(root)
                assertNull(state.boolValues[0])
                assertTrue(2L in state.intDomains[0])
                state.currentLevel = 1
                assertTrue(state.tightenIntMin(0, 2L))
                assertTrue(state.tightenIntMax(0, 2L))
                assertNull(state.runToFixpoint(allFactors = false))
                assertEquals(op == LinearOp.EQ, state.boolValues[0])
            }
        }
    }

    @Test
    fun `nonintegral wide single term targets decide both reification polarities`() {
        for (op in listOf(LinearOp.EQ, LinearOp.NE)) {
            val factor = ReifiedLinear(0, intArrayOf(0), arrayOf(w), op, w * bigIntOf(2L) + BIG_ONE)
            val session = PropagationSession(problem(factor, xHi = 4L, yHi = 0L))

            assertEquals(op == LinearOp.NE, session.boolValue(0))
        }
    }

    @Test
    fun `wide reified linear deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x1DE1)
        for (op in LinearOp.entries) {
            repeat(75) { iter ->
                val n = 3
                val problem = Problem(
                    numBoolVars = 1,
                    numIntVars = n,
                    intDomains = Array(n) { IntDomain(0, 3) },
                    factors = arrayOf<Factor>(
                        ReifiedLinear(
                            0,
                            IntArray(n) { it },
                            Array(n) { w * bigIntOf(listOf(-3, -2, -1, 1, 2, 3).random(rng).toLong()) },
                            op,
                            w * bigIntOf(rng.nextInt(-3, 7).toLong()),
                        ),
                    ),
                )
                PropagationReasonOracle.assertReasonsImply(problem, "wide-reified-linear-$op#$iter") { state ->
                    (rng.nextInt(3) != 0 || state.pinBool(0, rng.nextBoolean())) &&
                        (0 until 4).all {
                            val v = rng.nextInt(n)
                            when (rng.nextInt(3)) {
                                0 -> state.excludeIntValue(v, rng.nextInt(4).toLong())
                                1 -> state.tightenIntMin(v, 1L + rng.nextInt(2))
                                else -> state.tightenIntMax(v, 1L + rng.nextInt(2))
                            }
                        }
                }
            }
        }
    }
}
