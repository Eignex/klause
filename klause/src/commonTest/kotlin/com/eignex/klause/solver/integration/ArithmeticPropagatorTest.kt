package com.eignex.klause.solver.integration

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.propagatorProjection
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ArithmeticPropagatorTest {

    @Test
    fun `linear subscribes to only bound events on every term`() {
        val lin = Linear(intArrayOf(1, 2, -1), intArrayOf(0, 1, 2), LinearOp.LE, 5)
        val watches = lin.propagatorProjection().initialIntEventWatches!!
        val pairs = watches.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet()
        assertEquals(
            setOf(
                0 to IntEvent.LB_RAISED,
                0 to IntEvent.UB_LOWERED,
                1 to IntEvent.LB_RAISED,
                1 to IntEvent.UB_LOWERED,
                2 to IntEvent.LB_RAISED,
                2 to IntEvent.UB_LOWERED,
            ),
            pairs,
        )
        assertFalse(
            watches.any { IntEvent.kindOf(it) == IntEvent.VALUE_REMOVED || IntEvent.kindOf(it) == IntEvent.FIXED },
            "linear bound propagation reads only min/max, so it must not subscribe to interior/fixed events",
        )
    }

    private fun assertBoundOnly(watches: IntArray?, vars: IntArray) {
        val pairs = watches!!.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet()
        val expected = vars.toHashSet().flatMap { v ->
            listOf(
                v to IntEvent.LB_RAISED,
                v to IntEvent.UB_LOWERED,
            )
        }.toSet()
        assertEquals(expected, pairs)
        assertFalse(
            watches.any { IntEvent.kindOf(it) == IntEvent.VALUE_REMOVED || IntEvent.kindOf(it) == IntEvent.FIXED },
        )
    }

    @Test
    fun `product and array-minmax subscribe to only bound events`() {
        assertBoundOnly(
            Product(a = 0, b = 1, result = 2).propagatorProjection().initialIntEventWatches,
            intArrayOf(0, 1, 2),
        )
        assertBoundOnly(
            ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = true).propagatorProjection().initialIntEventWatches,
            intArrayOf(0, 1, 2, 3),
        )
    }

    @Test
    fun `array minmax deductions are implied by their reasons under carved holes`() {
        val rng = Random(0xA4A0)
        for (max in listOf(false, true)) {
            repeat(150) { iter ->
                val problem = Problem(
                    numBoolVars = 0,
                    numIntVars = 4,
                    intDomains = Array(4) { IntDomain(0, 3) },
                    factors = arrayOf<Factor>(ArrayMinMax(result = 0, xs = intArrayOf(1, 2, 3), max = max)),
                )
                PropagationReasonOracle.assertReasonsImply(problem, "array-minmax-$max#$iter") { state ->
                    (0 until 5).all {
                        val v = rng.nextInt(4)
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

    @Test
    fun `product deductions are implied by their reasons under carved holes`() {
        val rng = Random(0x940D)
        repeat(300) { iter ->
            val problem = Problem(
                numBoolVars = 0,
                numIntVars = 3,
                intDomains = arrayOf(IntDomain(-2, 2), IntDomain(-2, 2), IntDomain(-4, 4)),
                factors = arrayOf<Factor>(Product(a = 0, b = 1, result = 2)),
            )
            PropagationReasonOracle.assertReasonsImply(problem, "product#$iter") { state ->
                (0 until 4).all {
                    val v = rng.nextInt(3)
                    val span = if (v == 2) 4 else 2
                    when (rng.nextInt(3)) {
                        0 -> state.excludeIntValue(v, rng.nextInt(-span, span + 1).toLong())
                        1 -> state.tightenIntMin(v, rng.nextInt(-span + 1, 1).toLong())
                        else -> state.tightenIntMax(v, rng.nextInt(0, span).toLong())
                    }
                }
            }
        }
    }
}
