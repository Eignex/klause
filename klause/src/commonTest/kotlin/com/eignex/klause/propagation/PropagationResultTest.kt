package com.eignex.klause.propagation

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class PropagationResultTest {
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
    fun `merge should intersect set-restrictions on the same variable`() {
        val merged = setOf(2, longArrayOf(1, 3, 5)).merge(setOf(2, longArrayOf(3, 5, 7)))
        assertEquals(mapOf(2 to listOf(3L, 5L)), sets(merged))
    }

    @Test
    fun `merge should retain an empty survivor intersection`() {
        val merged = setOf(2, longArrayOf(1, 3)).merge(setOf(2, longArrayOf(5, 7)))

        assertEquals(mapOf(2 to emptyList()), sets(merged))
    }

    @Test
    fun `merge should union holes once in variable and value order`() {
        val left = PropagationResult.Implied.EMPTY.withHole(7, Long.MAX_VALUE).withHole(2, 3)
        val right = PropagationResult.Implied.EMPTY.withHole(7, Long.MIN_VALUE)
            .withHole(2, 3).withHole(0, 0).withHole(2, -1)

        val merged = left.merge(right)

        assertContentEquals(intArrayOf(0, 2, 2, 7, 7), merged.intHoleVarIds)
        assertContentEquals(longArrayOf(0, -1, 3, Long.MIN_VALUE, Long.MAX_VALUE), merged.intHoleValues)
    }

    @Test
    fun `adding a hole should leave the original deductions unchanged`() {
        val original = PropagationResult.Implied.EMPTY.withHole(2, 4)

        val extended = original.withHole(2, 3).withHole(2, 4)

        assertContentEquals(longArrayOf(4), original.intHoleValues)
        assertContentEquals(longArrayOf(3, 4), extended.intHoleValues)
    }

    @Test
    fun `merge should drop holes for a variable pinned in either input`() {
        val holes = PropagationResult.Implied.EMPTY.withHole(2, 4).withHole(7, 3)
        val pinTwo = PropagationResult.Implied(ints = mapOf(2 to 3))

        for (merged in listOf(holes.merge(pinTwo), pinTwo.merge(holes))) {
            assertContentEquals(intArrayOf(7), merged.intHoleVarIds)
            assertContentEquals(longArrayOf(3), merged.intHoleValues)
        }
    }

    @Test
    fun `merge should drop the set-restriction of a variable pinned in the union`() {
        val pinTwo = PropagationResult.Implied(ints = mapOf(2 to 3))
        val merged = setOf(2, longArrayOf(1, 3, 5)).merge(pinTwo)
        assertEquals(emptyMap(), sets(merged))
    }
}
