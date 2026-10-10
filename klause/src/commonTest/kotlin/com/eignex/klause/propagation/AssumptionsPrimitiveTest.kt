package com.eignex.klause.propagation

import kotlin.test.Test
import kotlin.test.assertEquals

class AssumptionsPrimitiveTest {

    @Test
    fun `mergedWith last-write-wins semantics`() {
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
    fun `withBool inserts and overwrites`() {
        val a = Assumptions(bools = mapOf(2 to true))
        val b = a.withBool(0, false).withBool(5, true).withBool(2, false)
        assertEquals(listOf(0, 2, 5), b.boolKeys.toList())
        assertEquals(listOf(false, false, true), b.boolValues.toList())
    }

}
