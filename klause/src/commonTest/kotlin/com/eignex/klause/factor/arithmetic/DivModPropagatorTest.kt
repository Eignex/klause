package com.eignex.klause.factor.arithmetic

import com.eignex.klause.factor.PropagationReasonOracle
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals

class DivModPropagatorTest {
    @Test
    fun `tuple supports should retain separated quotient values`() {
        val problem = Problem(
            0,
            3,
            arrayOf(
                IntDomain(1, 5).excludeValue(2).excludeValue(3).excludeValue(4),
                IntDomain(0, 2),
                IntDomain(0, 1),
            ),
            arrayOf<Factor>(DivMod(0, bigIntOf(2), 1, 2)),
        )

        val session = PropagationSession(problem)

        assertEquals(IntDomain(0, 2).excludeValue(1), session.intDomain(1))
    }

    @Test
    fun `coupled filtering explanations should retain bound and hole premises`() {
        for (convention in DivisionConvention.entries) {
            for ((variable, value) in listOf(0 to -2L, 1 to 1L, 2 to 0L, 3 to 0L)) {
                val source = Problem(
                    0,
                    4,
                    Array(4) { IntDomain(-2, 2) },
                    arrayOf<Factor>(DivMod(0, 1, 2, 3, convention)),
                )
                PropagationReasonOracle.assertReasonsImply(source, "$convention $variable $value") { state ->
                    state.excludeIntValue(variable, value)
                }
            }
        }
    }
}
