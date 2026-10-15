package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.propagation.IntEvent
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.minus
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ProductTest {
    @Test
    fun `fixed minimum operands should preserve aliased result solutions during reduction`() {
        for (product in listOf(Product(0, 1, 0), Product(1, 0, 0))) {
            val domains = arrayOf(IntDomain(Long.MIN_VALUE, Long.MIN_VALUE), IntDomain(-1, 1))
            val reduction = assertIs<FactorReduction.Rewrite>(product.structuralReduce(domains))
            val row = assertNotNull(reduction.replacement.single().linearForm).rows.single()
            val constants = row.constants as IntegralConstants
            for (operand in -1L..1L) {
                val values = longArrayOf(Long.MIN_VALUE, operand)
                var activity = BIG_ZERO
                for (term in 0 until row.size) {
                    activity += constants.exactCoeff(term) * bigIntOf(values[Term.intVar(row.ref(term))])
                }

                assertEquals(operand == 1L, activity == constants.exactBound, "$product $operand")
            }
        }
    }

    @Test
    fun `wide fixed product reduction should retain aliased roles after remapping`() {
        val product = Product(0, 1, 2)
        val domains = arrayOf(
            IntDomain(Long.MIN_VALUE, Long.MIN_VALUE),
            IntDomain(-1, 1),
            IntDomain(Long.MIN_VALUE, Long.MAX_VALUE),
        )
        val reduction = assertIs<FactorReduction.Rewrite>(product.structuralReduce(domains))
        val mapped = reduction.replacement.single().remap(VarRemap(intArrayOf(), intArrayOf(0, 1, 1)))
        val row = assertNotNull(mapped.linearForm).rows.single()
        val constants = row.constants as IntegralConstants
        for (value in -1L..1L) {
            var activity = BIG_ZERO
            for (term in 0 until row.size) {
                activity += constants.exactCoeff(term) * bigIntOf(value)
            }

            val sourceResidual = bigIntOf(value) - bigIntOf(Long.MIN_VALUE) * bigIntOf(value)
            assertEquals(sourceResidual, activity)
        }
    }

    @Test
    fun `Product deductions should be implied by their reasons under holes`() {
        val rng = Random(0x940D)
        repeat(80) { iter ->
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

    @Test
    fun `Product should subscribe only to bound events`() {
        val watches = Product(a = 0, b = 1, result = 2).propagatorProjection().initialIntEventWatches!!
        val expected = (0 until 3).flatMap { listOf(it to IntEvent.LB_RAISED, it to IntEvent.UB_LOWERED) }.toSet()
        assertEquals(expected, watches.map { IntEvent.intVarOf(it) to IntEvent.kindOf(it) }.toSet())
    }
}
