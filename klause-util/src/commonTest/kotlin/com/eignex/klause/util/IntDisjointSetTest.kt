package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Coverage for [IntDisjointSet]: find/union/connected semantics, transitivity, path-compression
 *  idempotence, and the [IntDisjointSet.groups] partition. */
class IntDisjointSetTest {

    @Test
    fun `fresh set has every element as its own singleton`() {
        val ds = IntDisjointSet(4)
        for (x in 0 until 4) assertEquals(x, ds.find(x))
        assertFalse(ds.connected(0, 1))
    }

    @Test
    fun `union connects two elements`() {
        val ds = IntDisjointSet(3)
        ds.union(0, 2)
        assertTrue(ds.connected(0, 2))
        assertFalse(ds.connected(0, 1))
        assertEquals(ds.find(0), ds.find(2))
    }

    @Test
    fun `union is transitive across a chain`() {
        val ds = IntDisjointSet(5)
        ds.union(0, 1)
        ds.union(1, 2)
        ds.union(3, 4)
        assertTrue(ds.connected(0, 2))
        assertFalse(ds.connected(2, 3))
        assertTrue(ds.connected(3, 4))
    }

    @Test
    fun `union of already-connected elements is a no-op`() {
        val ds = IntDisjointSet(3)
        ds.union(0, 1)
        ds.union(1, 0)
        assertTrue(ds.connected(0, 1))
    }

    @Test
    fun `repeated find returns a stable representative for a merged component`() {
        val ds = IntDisjointSet(4)
        ds.union(0, 1)
        ds.union(2, 3)
        ds.union(1, 3)
        val first = ds.find(0)
        assertEquals(first, ds.find(0))
        for (x in 0 until 4) assertEquals(first, ds.find(x))
    }

    @Test
    fun `groups partitions all elements into ascending members`() {
        val ds = IntDisjointSet(6)
        ds.union(0, 2)
        ds.union(2, 4)
        ds.union(1, 5)
        val groups = ds.groups().map { it.toList() }.toSet()
        assertEquals(setOf(listOf(0, 2, 4), listOf(1, 5), listOf(3)), groups)
    }

    @Test
    fun `groups returns one singleton per element when nothing is unioned`() {
        val ds = IntDisjointSet(3)
        val groups = ds.groups()
        assertEquals(3, groups.size)
        assertEquals(setOf(listOf(0), listOf(1), listOf(2)), groups.map { it.toList() }.toSet())
    }
}
