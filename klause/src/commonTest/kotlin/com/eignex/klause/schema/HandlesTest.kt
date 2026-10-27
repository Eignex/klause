package com.eignex.klause.schema

import kotlin.test.Test
import kotlin.test.assertEquals

class HandlesTest {
    @Test
    fun `a constant left of a float comparison should mirror the constant on the right`() {
        val f = FloatHandle("f", 0.0, 4.0) + 1.0

        assertEquals(
            listOf(f ge 0.5, f gt 0.5, f le 0.5, f lt 0.5, f eq 0.5, f ne 0.5),
            listOf(0.5 le f, 0.5 lt f, 0.5 ge f, 0.5 gt f, 0.5 eq f, 0.5 ne f),
        )
    }
}
