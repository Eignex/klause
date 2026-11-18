package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.Problem
import com.eignex.klause.util.ExecutionControl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IndependentSourceWitnessTest {
    @Test
    fun `unused real declarations without a finite value should withhold witness composition`() {
        for ((lower, upper) in listOf(
            Double.NaN to 1.0,
            0.0 to Double.NaN,
            Double.POSITIVE_INFINITY to Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY to Double.NEGATIVE_INFINITY,
            2.0 to 1.0,
        )) {
            val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(lower), realUpper = doubleArrayOf(upper))

            val point = joinIndependentWitnesses(source, emptyList(), ExecutionControl.Never)

            assertNull(point)
        }
    }

    @Test
    fun `unused real coordinates should receive a value inside their declared bounds`() {
        for ((lower, upper, expected) in listOf(
            Triple(2.0, 3.0, "2"),
            Triple(Double.NEGATIVE_INFINITY, -2.0, "-2"),
            Triple(Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, "0"),
        )) {
            val source = Problem(0, 0, emptyArray(), emptyArray(), numRealVars = 1,
                realLower = doubleArrayOf(lower), realUpper = doubleArrayOf(upper))

            val point = joinIndependentWitnesses(source, emptyList(), ExecutionControl.Never)

            assertEquals(expected, assertNotNull(point).realValue(0))
        }
    }
}
