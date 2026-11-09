package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeWatchEntriesTest {
    @Test
    fun `posting values should survive growth across page boundaries`() {
        for (capacity in listOf(0, 1, 15, 16, 17, 31, 32, 33)) {
            val entries = NativeWatchEntries(capacity)

            repeat(65) { entries.add(it.toLong() shl 32 or (64 - it).toLong()) }

            assertEquals(List(65) { it.toLong() shl 32 or (64 - it).toLong() },
                List(entries.size) { entries[it] })
        }
    }

    @Test
    fun `removing the final posting in a page should release its primitive capacity`() {
        val entries = NativeWatchEntries()
        repeat(33) { entries.add(it.toLong()) }
        val before = entries.primitiveBytes

        entries.removeLast()

        assertEquals(32, entries.size)
        assertTrue(entries.primitiveBytes < before)
        assertEquals(List(32) { it.toLong() }, List(entries.size) { entries[it] })
    }

    @Test
    fun `truncation should release every page beyond the retained postings`() {
        val entries = NativeWatchEntries()
        repeat(65) { entries.add(it.toLong()) }

        entries.truncateTo(0)

        assertEquals(0, entries.size)
        assertEquals(16L, entries.primitiveBytes)
    }

    @Test
    fun `retirement tracking should remain in the posting primitive ownership`() {
        val entries = NativeWatchEntries()
        entries.retirementEpoch = 7
        val sample = SharingMemoryObservation(1000L, 32, { false }, {})

        val memory = assertNotNull(sample.snapshot { entries.sharingMemory(sample) { false } }.completeValues)

        assertEquals(16L, memory.getValue("ownedPrimitiveBytes"))
    }

    @Test
    fun `a protected posting should not protect a different page`() {
        val entries = NativeWatchEntries()
        repeat(32) { entries.add(it.toLong()) }
        val first = SharingMemoryObservation(1000L, 32, { false }, {})
        val firstPage = assertNotNull(first.snapshot { entries.sharingMemory(first) { it == 0L } }.completeValues)
        val all = SharingMemoryObservation(1000L, 32, { false }, {})

        val allPages = assertNotNull(all.snapshot { entries.sharingMemory(all) { true } }.completeValues)

        assertEquals(firstPage.getValue("ownedPrimitiveBytes"), allPages.getValue("ownedPrimitiveBytes"))
        assertEquals(16L * Long.SIZE_BYTES,
            allPages.getValue("protectedPrimitiveBytes") - firstPage.getValue("protectedPrimitiveBytes"))
    }
}
