package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.propagatorProjection
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ArrayMinMaxTest {
    @Test
    fun `a singleton extremum equated to itself should reduce to a tautology`() {
        for (max in listOf(false, true)) {
            val factor = ArrayMinMax(0, intArrayOf(0), max)

            val reduced = factor.structuralReduce(arrayOf(IntDomain(0, 2)))

            assertTrue(assertIs<FactorReduction.Rewrite>(reduced).replacement.isEmpty())
        }
    }

    @Test
    fun `ArrayMinMax deductions should be implied by their reasons under holes`() {
        val rng = Random(0xA4A0)
        for (max in listOf(false, true)) {
            repeat(40) { iter ->
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
    fun `ArrayMinMax should subscribe only to bound events`() {
        val minMax = ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = true)
        val watches = minMax.propagatorProjection().initialIntEventWatches!!
        val expected = (0 until 4).flatMap { listOf(it to IntEvent.LB_RAISED, it to IntEvent.UB_LOWERED) }.toSet()
        assertEquals(expected, watches.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet())
    }
}
