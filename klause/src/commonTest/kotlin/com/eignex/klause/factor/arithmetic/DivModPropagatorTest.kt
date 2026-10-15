package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import kotlin.test.Test

class DivModPropagatorTest {
    @Test
    fun `coupled filtering explanations retain bound and hole premises`() {
        for (convention in DivisionConvention.entries) {
            for ((variable, value) in listOf(0 to -2L, 1 to 1L, 2 to 0L, 3 to 0L)) {
                val source = Problem(0, 4, Array(4) { IntDomain(-2, 2) },
                    arrayOf<Factor>(DivMod(0, 1, 2, 3, convention)))
                PropagationReasonOracle.assertReasonsImply(source, "$convention $variable $value") { state ->
                    state.excludeIntValue(variable, value)
                }
            }
        }
    }
}
