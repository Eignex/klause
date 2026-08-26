package com.eignex.klause.lowering

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DecimalScalingTest {

    private fun scaleOf(vararg values: Double): RowScale =
        RowScaleBuilder().also { builder -> values.forEach { builder.observe(it) } }.resolve()

    @Test
    fun `a row of whole values needs no multiplier`() {
        val scale = scaleOf(1.0, -3.0, 42.0)

        assertEquals(1L, assertIs<RowScale.Exact>(scale).multiplier)
    }

    @Test
    fun `a decimal row takes the least common denominator of its values`() {
        val scale = scaleOf(0.5, 0.125, 4.0)

        assertEquals(1000L, assertIs<RowScale.Exact>(scale).multiplier)
        assertEquals(125L, scale.scale(0.125))
        assertEquals(500L, scale.scale(0.5))
    }

    @Test
    fun `a value finer than a millionth keeps a whole coefficient`() {
        val scale = scaleOf(1e-7, 1.0)

        assertEquals(1L, scale.scale(1e-7), "a term the row states may not scale to nothing")
    }

    @Test
    fun `a value printed at full double precision is rounded rather than restated`() {
        val scale = scaleOf(-0.0214054799999994)

        assertIs<RowScale.Rounded>(scale)
    }

    @Test
    fun `a row wider than any single multiplier is unrepresentable`() {
        val scale = scaleOf(1e16, 1e-3)

        assertEquals(RowScale.Unrepresentable, scale)
    }
}
