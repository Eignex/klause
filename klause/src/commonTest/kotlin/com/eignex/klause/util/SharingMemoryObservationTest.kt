package com.eignex.klause.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    @Test
    fun `many distinct owners and protection upgrades should fit the default observation grant`() {
        val owners = Array(384) { Any() }
        val sample = SharingMemoryObservation(SharingMemoryObservation.DEFAULT_WORK,
            SharingMemoryObservation.DEFAULT_SCRATCH_ENTRIES, { false }, {})

        val memory = sample.snapshot {
            for (owner in owners) sample.owned(owner, 8L)
            for (owner in owners.reversed()) sample.owned(owner, 8L, protected = true)
        }

        val values = assertNotNull(memory.completeValues)
        assertEquals(384L * 8L, values["ownedPrimitiveBytes"])
        assertEquals(384L * 8L, values["protectedPrimitiveBytes"])
        assertTrue(memory.work <= SharingMemoryObservation.DEFAULT_WORK)
        assertEquals(384, memory.scratchEntries)
    }

    @Test
    fun `primitive and reference regions of one owner should retain separate protection`() {
        val owner = Any()
        val sample = SharingMemoryObservation(1024L, 128, { false }, {})

        val memory = sample.snapshot {
            sample.owned(owner, 16L)
            sample.ownedReferences(owner, 3L, protected = true)
            sample.owned(owner, 16L, protected = true)
            sample.ownedReferences(owner, 3L)
        }

        val values = assertNotNull(memory.completeValues)
        assertEquals(16L, values["ownedPrimitiveBytes"])
        assertEquals(16L, values["protectedPrimitiveBytes"])
        assertEquals(3L, values["ownedReferenceSlots"])
        assertEquals(3L, values["protectedReferenceSlots"])
    }

    @Test
    fun `owner observation should bypass user equality and hash code`() {
        val owners = List(4) {
            object {
                override fun equals(other: Any?): Boolean = error("User equality")
                override fun hashCode(): Int = error("User hash code")
            }
        }
        val sample = SharingMemoryObservation(1024L, 128, { false }, {})

        val memory = sample.snapshot {
            for (owner in owners) sample.owned(owner, 8L)
            for (owner in owners) sample.owned(owner, 8L, protected = true)
        }

        val values = assertNotNull(memory.completeValues)
        assertEquals(32L, values["ownedPrimitiveBytes"])
        assertEquals(32L, values["protectedPrimitiveBytes"])
    }

    @Test
    fun `an unfunded distinct owner should leave the observation partial`() {
        val owner = Any()
        val other = Any()
        val sample = SharingMemoryObservation(1024L, 1, { false }, {})

        val memory = sample.snapshot {
            sample.owned(owner, 8L)
            sample.owned(owner, 8L, protected = true)
            sample.owned(other, 16L)
        }

        assertEquals(SharingMemoryStatus.PARTIAL, memory.status)
        assertEquals(1, memory.scratchEntries)
        assertNull(memory.completeValues)
    }
    @Test
    fun `protected ids with long shared hash prefixes should fit the default grant`() {
        val sample = SharingMemoryObservation(SharingMemoryObservation.DEFAULT_WORK,
            SharingMemoryObservation.DEFAULT_SCRATCH_ENTRIES, { false }, {})
        val ids = sample.seenIds()

        val memory = sample.snapshot {
            repeat(384) { assertTrue(sample.retain(ids, it shl 23)) }
            repeat(384) { assertFalse(sample.retain(ids, (383 - it) shl 23)) }
            for (index in listOf(0, 127, 255, 383)) assertTrue(sample.contains(ids, index shl 23))
            assertFalse(sample.contains(ids, -1))
        }

        assertEquals(SharingMemoryStatus.COMPLETE, memory.status)
        assertEquals(384, ids.size)
        assertEquals(384, memory.scratchEntries)
    }

    @Test
    fun `equal payload contents should retain distinct array identities`() {
        val first = intArrayOf(1, 2)
        val second = intArrayOf(1, 2)
        val sample = SharingMemoryObservation(1024L, 128, { false }, {})
        val arrays = sample.seenIdentities<IntArray>()

        val memory = sample.snapshot {
            assertTrue(sample.retain(arrays, first))
            assertTrue(sample.retain(arrays, second))
            assertFalse(sample.retain(arrays, first))
            assertTrue(sample.contains(arrays, first))
            assertTrue(sample.contains(arrays, second))
        }

        assertEquals(SharingMemoryStatus.COMPLETE, memory.status)
        assertEquals(2, arrays.size)
    }
}
