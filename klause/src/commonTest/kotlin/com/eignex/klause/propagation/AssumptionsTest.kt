package com.eignex.klause.propagation

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AssumptionsTest {

    @Test
    fun `merged boolean pins should retain trailing false values across word boundaries`() {
        for (size in listOf(1, 63, 64, 65, 129)) {
            val original = Assumptions(bools = (0 until size).associateWith { true })
            val updates = Assumptions(bools = ((1 until size step 2) + (size - 1)).associateWith { false })

            val merged = original.mergedWith(updates)

            assertContentEquals(IntArray(size) { it }, merged.boolKeys)
            assertContentEquals(BooleanArray(size) { it % 2 == 0 && it != size - 1 }, merged.boolValues)
        }
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
