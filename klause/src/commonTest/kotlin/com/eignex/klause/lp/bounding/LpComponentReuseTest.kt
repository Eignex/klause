package com.eignex.klause.lp.bounding

import com.eignex.klause.lp.engine.LpComponentShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LpComponentReuseTest {
    @Test
    fun `inspection declines small or oversized owners before partition allocation`() {
        assertEquals("SMALL_MODEL", componentReuseInspection(8, 8, 16))
        assertEquals("STORAGE", componentReuseInspection(10_000, 10_000, 200_000))
        assertNull(componentReuseInspection(32, 128, 256))
    }

    @Test
    fun `admission declines shapes whose retained cost cannot repay splitting`() {
        for ((shape, expected) in listOf(
            LpComponentShape(2, 4096, 2048, 4096) to "ADMITTED",
            LpComponentShape(1, 4096, 2048, 4096) to "COMPONENT_COUNT",
            LpComponentShape(1025, 4096, 2048, 4096) to "COMPONENT_COUNT",
            LpComponentShape(2, 4095, 2048, 4096) to "SMALL_MODEL",
            LpComponentShape(2, 4096, 2048, 16L * 1024 * 1024 + 1L) to "STORAGE",
            LpComponentShape(2, 4096, 3073, 4096) to "DOMINANT_COMPONENT",
        )) {
            assertEquals(expected, componentReuseAdmission(shape))
        }
    }

    @Test
    fun `numerical reuse does not request an lp technique`() {
        for (enabled in listOf(false, true)) {
            assertTrue(LpPlan(componentReuse = enabled).hasDefaultNumericalSettings())
        }
    }
}
