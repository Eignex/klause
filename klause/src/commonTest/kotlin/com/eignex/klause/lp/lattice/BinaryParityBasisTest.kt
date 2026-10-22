package com.eignex.klause.lp.lattice

import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BinaryParityBasisTest {
    @Test
    fun `coupled equalities should expose an impossible parity with both premises`() {
        val work = BinaryParityWork(Cancellation.Never)
        val basis = BinaryParityBasis(work)
        val even = assertNotNull(BinaryParityRow.create(intArrayOf(0, 1, 2), false, work))
        val odd = assertNotNull(BinaryParityRow.create(intArrayOf(0, 1, 2), true, work))
        assertFalse(assertNotNull(basis.add(even, 7)).impossible)

        val result = assertNotNull(basis.add(odd, 9))

        assertTrue(result.impossible)
        assertEquals(listOf(7, 9), List(result.supportSize, result::supportAt))
    }

    @Test
    fun `compatible or unresolved residues should supply no impossibility claim`() {
        val work = BinaryParityWork(Cancellation.Never)
        val basis = BinaryParityBasis(work)
        val premise = assertNotNull(BinaryParityRow.create(intArrayOf(0, 1, 2), false, work))
        assertNotNull(basis.add(premise, 0))
        val unresolved = assertNotNull(BinaryParityRow.create(intArrayOf(0, 1, 3), true, work))

        assertFalse(assertNotNull(basis.reduce(premise)).impossible)
        assertFalse(assertNotNull(basis.reduce(unresolved)).impossible)
    }

    @Test
    fun `parity rows should retain duplicate cancellation after input mutation`() {
        val work = BinaryParityWork(Cancellation.Never)
        val columns = intArrayOf(3, 1, 3, 2, 1)
        val row = assertNotNull(BinaryParityRow.create(columns, true, work))
        val basis = BinaryParityBasis(work)
        assertNotNull(basis.add(row, 4))
        columns.fill(99)
        val target = assertNotNull(BinaryParityRow.create(intArrayOf(2), false, work))

        val result = assertNotNull(basis.reduce(target))

        assertTrue(result.impossible)
        assertEquals(listOf(4), List(result.supportSize, result::supportAt))
    }

    @Test
    fun `combined parity conflicts should retain every contributing equation`() {
        val work = BinaryParityWork(Cancellation.Never)
        val basis = BinaryParityBasis(work)
        assertNotNull(basis.add(assertNotNull(BinaryParityRow.create(intArrayOf(0, 1), false, work)), 0))
        assertNotNull(basis.add(assertNotNull(BinaryParityRow.create(intArrayOf(1, 2), false, work)), 1))
        val target = assertNotNull(BinaryParityRow.create(intArrayOf(0, 2), true, work))

        val result = assertNotNull(basis.add(target, 2))

        assertTrue(result.impossible)
        assertEquals(listOf(0, 1, 2), List(result.supportSize, result::supportAt))
    }

    @Test
    fun `cancellation should withhold a parity proof from a retained basis`() {
        var stopped = false
        val work = BinaryParityWork(Cancellation { stopped })
        val basis = BinaryParityBasis(work)
        val even = assertNotNull(BinaryParityRow.create(intArrayOf(0, 1), false, work))
        val odd = assertNotNull(BinaryParityRow.create(intArrayOf(0, 1), true, work))
        assertNotNull(basis.add(even, 0))
        stopped = true

        assertNull(basis.reduce(odd))
        assertTrue(work.cancelled)
    }

    @Test
    fun `optional parity capacity refusal should not report containing cancellation`() {
        val work = BinaryParityWork(Cancellation.Never)

        val result = BinaryParityRow.create(IntArray(4097) { it }, false, work)

        assertNull(result)
        assertTrue(work.refused)
        assertFalse(work.cancelled)
    }

    @Test
    fun `reused input identities should not authorize a parity proof`() {
        val work = BinaryParityWork(Cancellation.Never)
        val basis = BinaryParityBasis(work)
        val even = assertNotNull(BinaryParityRow.create(intArrayOf(0, 1), false, work))
        val odd = assertNotNull(BinaryParityRow.create(intArrayOf(0, 1), true, work))
        assertNotNull(basis.add(even, 0))

        assertNull(basis.add(odd, 0))
        assertTrue(work.refused)
    }
}
