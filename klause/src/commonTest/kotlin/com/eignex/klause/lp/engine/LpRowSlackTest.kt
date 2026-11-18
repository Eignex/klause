package com.eignex.klause.lp.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LpRowSlackTest {
    @Test
    fun `an unpriced cut should withhold positive tolerance pricing and retain exact pricing`() {
        assertEquals(Double.POSITIVE_INFINITY, UNPRICED_CUT_SLACK.at(LpBoundSlack(1.0, 0.0)))
        assertEquals(Double.POSITIVE_INFINITY, UNPRICED_CUT_SLACK.at(LpBoundSlack(0.0, 0.25)))
        assertEquals(0.0, UNPRICED_CUT_SLACK.at(LpBoundSlack.NONE))
    }

    @Test
    fun `zero premise bands should not multiply an infinite rate or weight`() {
        val row = LpRowSlack(doubleArrayOf(Double.POSITIVE_INFINITY), doubleArrayOf(0.0))

        val displacement = row.at(LpBoundSlack(0.0, Double.POSITIVE_INFINITY))

        assertEquals(0.0, displacement)
    }

    @Test
    fun `zero sides should retain only absolute slack under an unbounded relative rate`() {
        val slack = LpBoundSlack(2.0, Double.POSITIVE_INFINITY)

        val displacement = slack.at(0.0)

        assertEquals(2.0, displacement)
    }

    @Test
    fun `derived displacement should sum each weighted premise band`() {
        val row = LpRowSlack(doubleArrayOf(3.0, 2.0), doubleArrayOf(1.0, 16.0))

        val displacement = row.at(LpBoundSlack(2.0, 0.25))

        assertTrue(displacement >= 14.0 && displacement < 14.0 + 1e-12)
    }

    @Test
    fun `absolute displacement should not require a finite relative scale`() {
        val row = LpRowSlack(doubleArrayOf(2.0), doubleArrayOf(Double.POSITIVE_INFINITY))

        val displacement = row.at(LpBoundSlack(1.0, 0.0))

        assertTrue(displacement >= 2.0 && displacement < 2.0 + 1e-12)
    }

    @Test
    fun `exact evidence should leave a derived side unchanged`() {
        val row = LpRowSlack(doubleArrayOf(2.0), doubleArrayOf(Double.POSITIVE_INFINITY))

        val displacement = row.at(LpBoundSlack.NONE)

        assertEquals(0.0, displacement)
    }

    @Test
    fun `retained premise bands should not borrow mutable inputs`() {
        val multipliers = doubleArrayOf(2.0)
        val magnitudes = doubleArrayOf(4.0)
        val row = LpRowSlack(multipliers, magnitudes)
        multipliers[0] = 0.0
        magnitudes[0] = 0.0

        val displacement = row.at(LpBoundSlack(0.0, 0.5))

        assertTrue(displacement >= 4.0 && displacement < 4.0 + 1e-12)
    }
}
