package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.ClausePropagator
import com.eignex.util.arrays.emptyIntArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GeneralLearnedRowsTest {
    @Test
    fun `empty page release should preserve other learned rows and keys`() {
        val rows = GeneralLearnedRows()
        val clause = ClausePropagator(intArrayOf(0, 1), emptyIntArray(), intArrayOf(0, 2))
        repeat(32) { rows.add(clause, 17, it, it == 16, it, it.toLong() + 1L) }
        val bytes = rows.primitiveBytes
        val references = rows.referenceCapacity

        repeat(16) { rows.release(it) }

        assertEquals(32, rows.size)
        assertSame(RetiredLearnedPropagator, rows[0])
        assertEquals(8, rows[0, GeneralLearnedRows.STAGES])
        assertSame(clause, rows[16])
        assertEquals(17L, rows.admission(16))
        assertTrue(rows.primitiveBytes < bytes)
        assertTrue(rows.referenceCapacity < references)
    }

    @Test
    fun `remapping should move policy payload and identity together`() {
        val rows = GeneralLearnedRows()
        val clause = ClausePropagator(intArrayOf(0, 1), emptyIntArray(), intArrayOf(0, 2))
        repeat(32) { rows.add(clause, 17, it, false, it, it.toLong() + 1L) }
        val payload = intArrayOf(0, 1)
        rows.setPayload(16, payload)
        repeat(16) { rows.release(it) }

        rows.move(16, 0)
        rows.truncateTo(1)

        assertEquals(1, rows.size)
        assertSame(clause, rows[0])
        assertSame(payload, rows.payload(0))
        assertEquals(17L, rows.admission(0))
        assertEquals(16, rows[0, GeneralLearnedRows.LBD])
    }

    @Test
    fun `append after truncation should not inherit a former payload or reason count`() {
        val rows = GeneralLearnedRows()
        val clause = ClausePropagator(intArrayOf(0, 1), emptyIntArray(), intArrayOf(0, 2))
        repeat(4) { rows.add(clause, 17, 9, false, it, it.toLong()) }
        rows.setPayload(1, intArrayOf(1, 0))
        rows[1, GeneralLearnedRows.BOOL_REASONS] = 2
        rows.truncateTo(1)

        rows.add(clause, 16, 2, true, 7, 42L)

        assertEquals(null, rows.payload(1))
        assertEquals(0, rows[1, GeneralLearnedRows.BOOL_REASONS])
        assertEquals(16, rows[1, GeneralLearnedRows.STAGES])
        assertEquals(42L, rows.admission(1))
    }
    @Test
    fun `unadmitted payload slots should reject reads and writes`() {
        val rows = GeneralLearnedRows()
        val clause = ClausePropagator(intArrayOf(0, 1), emptyIntArray(), intArrayOf(0, 2))
        rows.add(clause, 16, 2, false, -1, 1L)

        assertFailsWith<IndexOutOfBoundsException> { rows.payload(1) }
        assertFailsWith<IndexOutOfBoundsException> { rows.setPayload(1, intArrayOf(0, 1)) }
        assertEquals(null, rows.payload(0))
    }

}
