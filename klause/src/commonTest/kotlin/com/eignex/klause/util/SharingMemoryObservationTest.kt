package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SharingMemoryObservationTest {
    @Test
    fun `a pool should report its protected subset without changing owned bytes`() {
        val sample = SharingMemoryObservation(1024L, 128, { false }, {})
        val pool = Any()

        val memory = sample.snapshot { sample.ownedPool(pool, 100L, 40L) }

        val values = assertNotNull(memory.completeValues)
        assertEquals(100L, values["ownedPrimitiveBytes"])
        assertEquals(40L, values["protectedPrimitiveBytes"])
    }

    @Test
    fun `pool protection upgrades should preserve a single owned identity`() {
        val sample = SharingMemoryObservation(1024L, 128, { false }, {})
        val pool = Any()

        val memory = sample.snapshot {
            sample.ownedPool(pool, 100L, 40L)
            sample.ownedPool(pool, 100L, 70L)
        }

        val values = assertNotNull(memory.completeValues)
        assertEquals(100L, values["ownedPrimitiveBytes"])
        assertEquals(70L, values["protectedPrimitiveBytes"])
    }

    @Test
    fun `a fully protected alias should cover the entire pool`() {
        val sample = SharingMemoryObservation(1024L, 128, { false }, {})
        val pool = Any()

        val memory = sample.snapshot {
            sample.ownedPool(pool, 100L, 40L)
            sample.owned(pool, 100L, protected = true)
        }

        val values = assertNotNull(memory.completeValues)
        assertEquals(100L, values["ownedPrimitiveBytes"])
        assertEquals(100L, values["protectedPrimitiveBytes"])
    }

    @Test
    fun `weaker pool protection should preserve prior coverage`() {
        val sample = SharingMemoryObservation(1024L, 128, { false }, {})
        val pool = Any()

        val memory = sample.snapshot {
            sample.ownedPool(pool, 100L, 70L)
            sample.ownedPool(pool, 100L, 40L)
        }

        val values = assertNotNull(memory.completeValues)
        assertEquals(70L, values["protectedPrimitiveBytes"])
    }

    @Test
    fun `pool protection should stay within its owned bytes`() {
        for (protected in listOf(-1L, 101L)) {
            val sample = SharingMemoryObservation(1024L, 128, { false }, {})

            assertFailsWith<IllegalArgumentException> { sample.ownedPool(Any(), 100L, protected) }
        }
    }

    @Test
    fun `pool reports should cover the same owned region within an observation`() {
        val sample = SharingMemoryObservation(1024L, 128, { false }, {})
        val pool = Any()
        sample.ownedPool(pool, 100L, 40L)

        assertFailsWith<IllegalArgumentException> { sample.ownedPool(pool, 120L, 40L) }
    }
}
