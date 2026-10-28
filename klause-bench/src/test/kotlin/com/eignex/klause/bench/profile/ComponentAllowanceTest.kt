package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentAllowanceTest {
    @Test
    fun `storage should remain charged after a child finishes`() {
        val allowance = ComponentAllowance(Cancellation.Never, maxBytes = 8L)

        assertTrue(allowance.retain(8L))
        assertFalse(allowance.retain(1L))
        assertEquals(9L, allowance.bytes)
        assertTrue(allowance.stop())
    }

    @Test
    fun `containing cancellation should stop structural analysis`() {
        val allowance = ComponentAllowance(Cancellation { true })

        assertFalse(allowance.visit())
    }

    @Test
    fun `visits should not be renewed between children`() {
        val allowance = ComponentAllowance(Cancellation.Never, maxVisits = 2L)

        assertTrue(allowance.visit(2L))
        assertFalse(allowance.visit())
        assertEquals(3L, allowance.visits)
    }
}
