package com.eignex.klause.ir

import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.toLinearObjective
import kotlin.test.Test
import kotlin.test.assertEquals

class LinearObjectiveSpecTest {
    @Test
    fun `lowered objective coefficients retain their captured meaning`() {
        val coefficients = longArrayOf(3)
        val spec = LinearObjectiveSpec(intCoefficients = coefficients)
        val copied = spec.copy()

        coefficients[0] = 7
        spec.intCoefficients[0] = 11
        copied.component2()[0] = 13

        assertEquals(15L, spec.toLinearObjective().evaluateLong(Sample(BooleanArray(0), longArrayOf(5))))
        assertEquals(spec, copied)
    }
}
