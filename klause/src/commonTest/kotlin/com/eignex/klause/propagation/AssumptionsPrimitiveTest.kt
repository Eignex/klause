package com.eignex.klause.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssumptionsPrimitiveTest {

    @Test
    fun `map constructor sorts keys ascending`() {
        val a = Assumptions(bools = mapOf(5 to true, 1 to false, 3 to true))
        assertEquals(listOf(1, 3, 5), a.boolKeys.toList())
        assertEquals(listOf(false, true, true), a.boolValues.toList())
    }

    @Test
    fun `boolValueOrNull and intValueOrNull return null for absent keys`() {
        val a = Assumptions(bools = mapOf(0 to true, 5 to false), ints = mapOf(2 to 7))
        assertEquals(true, a.boolValueOrNull(0))
        assertEquals(false, a.boolValueOrNull(5))
        assertNull(a.boolValueOrNull(1))
        assertEquals(7, a.intValueOrNull(2))
        assertNull(a.intValueOrNull(99))
    }

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
    fun `mergedWith with None is identity on both sides`() {
        val a = Assumptions(bools = mapOf(0 to true))
        assertEquals(a, a.mergedWith(Assumptions.None))
        assertEquals(a, Assumptions.None.mergedWith(a))
    }

    @Test
    fun `isFrozen reflects sorted-array membership`() {
        val a = Assumptions(bools = mapOf(2 to true), ints = mapOf(7 to 1))
        assertTrue(a.isFrozenBool(2))
        assertFalse(a.isFrozenBool(3))
        assertTrue(a.isFrozenInt(7))
        assertFalse(a.isFrozenInt(0))
    }

    @Test
    fun `forEach iterates in ascending key order`() {
        val a = Assumptions(bools = mapOf(7 to true, 1 to false, 4 to true))
        val collected = ArrayList<Pair<Int, Boolean>>()
        a.forEachBool { id, v -> collected.add(id to v) }
        assertEquals(listOf(1 to false, 4 to true, 7 to true), collected)
    }

    @Test
    fun `withBool inserts and overwrites`() {
        val a = Assumptions(bools = mapOf(2 to true))
        val b = a.withBool(0, false).withBool(5, true).withBool(2, false)
        assertEquals(listOf(0, 2, 5), b.boolKeys.toList())
        assertEquals(listOf(false, false, true), b.boolValues.toList())
    }

    @Test
    fun `legacy bools and ints map views match the primitive arrays`() {
        val a = Assumptions(bools = mapOf(0 to true, 3 to false), ints = mapOf(4 to 1))
        assertEquals(mapOf(0 to true, 3 to false), a.bools)
        assertEquals(mapOf(4 to 1L), a.ints)
    }
}
