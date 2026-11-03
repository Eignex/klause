package com.eignex.klause.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntEventOverflowTest {
    @Test
    fun `small grants should release event entries beside a live posting`() {
        val index = IntEventOverflow()
        index.subscribe(0, 0)
        val bytes = index.primitiveCapacityBytes
        val references = index.referenceCapacity
        repeat(512) { index.subscribe((it + 1) * 4, it + 1) }

        repeat(2048) {
            val budget = ReclamationPreparation(40L, 256L, { false }, {})
            try { index.sweep(1, budget) { it != 0 } } catch (_: ReclamationPreparation.Deferred) { }
            assertTrue(budget.work <= 40L)
        }

        assertEquals(1, index.size)
        assertEquals(bytes, index.primitiveCapacityBytes)
        assertEquals(references, index.referenceCapacity)
        assertEquals(listOf(0), assertNotNull(index[0]).toIntArray().toList())
    }

    @Test
    fun `a later grant should retry an oversized surviving posting trim`() {
        val index = IntEventOverflow()
        repeat(128) { index.subscribe(4, it) }
        repeat(128) { index.subscribe(4, it + 128) }

        repeat(256) {
            val budget = ReclamationPreparation(40L, 256L, { false }, {})
            try { index.sweep(1, budget) { it >= 128 } } catch (_: ReclamationPreparation.Deferred) { }
        }
        assertEquals(128, assertNotNull(index[4]).size)
        assertEquals(256, assertNotNull(index[4]).backingData.size)
        repeat(8) {
            val budget = ReclamationPreparation(512L, 1024L, { false }, {})
            try { index.sweep(1, budget) { it >= 128 } } catch (_: ReclamationPreparation.Deferred) { }
        }

        assertEquals(128, assertNotNull(index[4]).backingData.size)
        assertEquals((0 until 128).toSet(), assertNotNull(index[4]).toSet())
    }

    @Test
    fun `new retirement behind the cursor should retain another cleanup opportunity`() {
        val index = IntEventOverflow()
        index.subscribe(0, 0)
        index.subscribe(0, 100)
        for (factor in 1..64) index.subscribe(1000, factor)
        repeat(8) {
            if (assertNotNull(index[0]).size > 1) {
                val budget = ReclamationPreparation(40L, 256L, { false }, {})
                try { index.sweep(1, budget) { it >= 100 } } catch (_: ReclamationPreparation.Deferred) { }
            }
        }
        assertEquals(1, assertNotNull(index[0]).size)
        index.subscribe(0, 101)
        var retry = true

        repeat(128) {
            if (retry) {
                val budget = ReclamationPreparation(40L, 256L, { false }, {})
                try { index.sweep(2, budget) { it >= 100 } } catch (_: ReclamationPreparation.Deferred) { }
                retry = budget.progressed
            }
        }

        assertEquals(listOf(0), assertNotNull(index[0]).toIntArray().toList())
        assertEquals((1..64).toSet(), assertNotNull(index[1000]).toSet())
    }
}
