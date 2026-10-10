package com.eignex.klause.propagation

import kotlin.test.Test
import kotlin.test.assertEquals

class PropagationResultImpliedMergeIntSetTest {

    private fun setOf(v: Int, survivors: LongArray): PropagationResult.Implied = PropagationResult.Implied(
        intSetKeys = intArrayOf(v),
        intSetOffsets = intArrayOf(0, survivors.size),
        intSetValues = survivors,
    )

    private fun sets(i: PropagationResult.Implied): Map<Int, List<Long>> {
        val m = HashMap<Int, List<Long>>()
        i.forEachIntSet { id, survivors -> m[id] = survivors.toList() }
        return m
    }

    @Test
    fun `merge intersects set-restrictions on the same variable`() {
        val merged = setOf(2, longArrayOf(1, 3, 5)).merge(setOf(2, longArrayOf(3, 5, 7)))
        assertEquals(mapOf(2 to listOf(3L, 5L)), sets(merged))
    }

    @Test
    fun `merge drops the set-restriction of a variable pinned in the union`() {
        val pinTwo = PropagationResult.Implied(ints = mapOf(2 to 3))
        val merged = setOf(2, longArrayOf(1, 3, 5)).merge(pinTwo)
        assertEquals(emptyMap(), sets(merged))
    }

}
