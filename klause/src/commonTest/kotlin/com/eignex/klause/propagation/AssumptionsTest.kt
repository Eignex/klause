package com.eignex.klause.propagation

import kotlin.test.Test
import kotlin.test.assertEquals

class AssumptionsTest {

    @Test
    fun `snapshot should preserve pins and deductions after caller storage changes`() {
        val original = Assumptions(
            intArrayOf(0), booleanArrayOf(true), intArrayOf(1), longArrayOf(3),
            DeducedRestrictions(
                intArrayOf(2), longArrayOf(4), intArrayOf(2), longArrayOf(9),
                intArrayOf(2), longArrayOf(5), intArrayOf(3), intArrayOf(0, 2), longArrayOf(6, 8),
            ),
        )
        val captured = original.snapshot()

        original.boolKeys[0] = 10
        original.boolValues[0] = false
        original.intKeys[0] = 11
        original.intValues[0] = 30
        original.deductions.intMinKeys[0] = 12
        original.deductions.intMinValues[0] = 40
        original.deductions.intMaxKeys[0] = 12
        original.deductions.intMaxValues[0] = 90
        original.deductions.intHoleVarIds[0] = 12
        original.deductions.intHoleValues[0] = 50
        original.deductions.intSetKeys[0] = 13
        original.deductions.intSetOffsets[1] = 1
        original.deductions.intSetValues[0] = 60

        assertEquals(mapOf(0 to true), captured.bools)
        assertEquals(mapOf(1 to 3L), captured.ints)
        assertEquals(4L, captured.deductions.intMinOrNull(2))
        assertEquals(9L, captured.deductions.intMaxOrNull(2))
        assertEquals(listOf(2), captured.deductions.intHoleVarIds.toList())
        assertEquals(listOf(5L), captured.deductions.intHoleValues.toList())
        assertEquals(listOf(3), captured.deductions.intSetKeys.toList())
        assertEquals(listOf(6L, 8L), captured.deductions.intSetSurvivorsAt(0).toList())
    }

    @Test
    fun `mergedWith should let the last write win`() {
        val a = Assumptions(bools = mapOf(0 to true, 1 to true), ints = mapOf(10 to 5))
        val b = Assumptions(bools = mapOf(1 to false, 2 to true), ints = mapOf(10 to 9, 20 to 3))
        val m = a.mergedWith(b)
        assertEquals(true, m.boolValueOrNull(0))
        assertEquals(false, m.boolValueOrNull(1))
        assertEquals(true, m.boolValueOrNull(2))
        assertEquals(9, m.intValueOrNull(10))
        assertEquals(3, m.intValueOrNull(20))
    }

    @Test
    fun `withBool should insert and overwrite`() {
        val a = Assumptions(bools = mapOf(2 to true))
        val b = a.withBool(0, false).withBool(5, true).withBool(2, false)
        assertEquals(listOf(0, 2, 5), b.boolKeys.toList())
        assertEquals(listOf(false, false, true), b.boolValues.toList())
    }
}
