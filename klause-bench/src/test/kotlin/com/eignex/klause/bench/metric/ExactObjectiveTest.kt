package com.eignex.klause.bench.metric

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExactObjectiveTest {
    @Test
    fun `decimal and rational text normalize to an exact value`() {
        for ((input, expected) in listOf("6/-9" to "-2/3", "1.25" to "5/4", "12e3" to "12000")) {
            assertEquals(expected, ExactObjective.parse(input).toString())
        }
    }

    @Test
    fun `malformed exact values cannot fall back to rounded legacy credit`() {
        for (input in listOf("NaN", "Infinity", "1/0", "1/2/3", "")) {
            assertNull(ExactObjective.value(input, 1.0))
        }
        assertEquals("5/4", ExactObjective.value(null, 1.25).toString())
    }
}
