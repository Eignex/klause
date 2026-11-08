package com.eignex.klause.propagation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReclamationPreparationTest {
    @Test
    fun `atomic admission should include its reservation and actual initialization`() {
        for (maximum in listOf(32L, 33L, 34L)) {
            val budget = ReclamationPreparation(maximum, 64L, { false }, {})

            val supported = budget.supportsAtomic(1L, 1L, 64L)

            assertEquals(maximum >= 34L, supported)
            assertEquals(0L, budget.work)
            if (supported) {
                budget.atomic(1L, 64L)
                budget.visit(1L)
                budget.ready()
                assertEquals(2L, budget.work)
            }
        }
    }

    @Test
    fun `atomic admission should not spend a tiny or scratch limited grant`() {
        val budget = ReclamationPreparation(1L, 63L, { false }, {})

        assertFalse(budget.supportsAtomic(1L, 0L, 64L))
        assertFalse(budget.supportsAtomic(1L, 1L, 0L))
        assertTrue(budget.supportsAtomic(0L, 0L, 63L))
        assertEquals(0L, budget.work)
    }
}
