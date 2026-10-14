package com.eignex.klause.factor.arithmetic

import com.eignex.klause.ir.FactorReduction
import com.eignex.klause.ir.IntegralConstants
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Term
import com.eignex.klause.util.BIG_ZERO
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.plus
import com.eignex.klause.util.times
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ProductTest {
    @Test
    fun `fixed minimum operands preserve aliased result solutions during reduction`() {
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
}
