package com.eignex.klause.solver.result

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MirUseDiagnosticsTest {
    @Test
    fun `LP owners should have distinct identities for equal seeds`() {
        val budget = assertNotNull(MirUseBudget.create())

        val first = assertNotNull(budget.owner(3L, 3, 0, 2, 0, 1))
        val second = assertNotNull(budget.owner(3L, 3, 0, 2, 0, 1))

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `invocations should retain distinct monotonic identities`() {
        val first = assertNotNull(MirUseBudget.create())

        val second = assertNotNull(MirUseBudget.create())

        assertTrue(second.invocationId > first.invocationId)
    }

    @Test
    fun `an invocation should refuse its ninth observed owner`() {
        val budget = assertNotNull(MirUseBudget.create())
        repeat(8) { assertNotNull(budget.owner(3L, 3, 0, 2, 0, 1)) }

        val refused = budget.owner(3L, 3, 0, 2, 0, 1)
        val snapshot = budget.finish()

        assertNull(refused)
        assertFalse(snapshot.complete)
        assertTrue(snapshot.retainedScalars <= 2048)
        assertTrue(snapshot.encoded.length <= 65536)
    }

    @Test
    fun `a second separation should not renew the first snapshot allowance`() {
        val budget = assertNotNull(MirUseBudget.create())
        val owner = assertNotNull(budget.owner(3L, 3, 0, 2, 0, 1))
        owner.begin(root = true, sourceMap = true)
        assertNotNull(owner.reserve(10L))
        owner.end()

        owner.begin(root = false, sourceMap = true)
        val repeated = owner.reserve(10L)
        val snapshot = budget.finish()

        assertNull(repeated)
        val words = snapshot.encoded.removeSurrounding("[", "]").split(',').map(String::toLong)
        assertEquals(1L, words[28])
        assertEquals(1L, words[29])
    }

    @Test
    fun `row and cut snapshot ceilings should refuse additional records`() {
        for ((cut, limit, size) in listOf(Triple(false, 48, 10L), Triple(true, 16, 8L))) {
            val budget = assertNotNull(MirUseBudget.create())
            val owner = assertNotNull(budget.owner(3L, 3, 0, 2, 0, 1))
            owner.begin(root = true, sourceMap = true)
            repeat(limit) {
                val offset = assertNotNull(owner.reserve(size, cut))
                owner.put(offset, if (cut) MirUseBudget.CUT_TAG else MirUseBudget.POTENTIAL_TAG)
                owner.put(offset + 1, size)
            }

            val refused = owner.reserve(size, cut)
            val snapshot = budget.finish()

            assertNull(refused)
            assertFalse(snapshot.complete)
        }
    }

    @Test
    fun `payload capacity should remain shared across owners`() {
        val budget = assertNotNull(MirUseBudget.create())
        val owners = List(8) { assertNotNull(budget.owner(it.toLong(), 3, 0, 2, 0, 1)) }
        owners.forEach { it.begin(root = true, sourceMap = true) }
        repeat(48) {
            owners.first().reserve(64L)?.let { offset ->
                owners.first().put(offset, MirUseBudget.POTENTIAL_TAG)
                owners.first().put(offset + 1, 64L)
            }
        }

        val other = owners[1].reserve(64L)
        val snapshot = budget.finish()

        assertNull(other)
        assertFalse(snapshot.complete)
        assertTrue(snapshot.retainedScalars <= 2048)
        assertTrue(2 * snapshot.encoded.length + 512 <= 65536)
    }

    @Test
    fun `unbounded metadata requests should decline before retention`() {
        val budget = assertNotNull(MirUseBudget.create())
        val owner = assertNotNull(budget.owner(3L, 3, 0, 2, 0, 1))
        owner.begin(root = true, sourceMap = true)

        val offset = owner.reserve(Long.MAX_VALUE)
        val snapshot = budget.finish()

        assertNull(offset)
        assertFalse(snapshot.complete)
        assertTrue(snapshot.encoded.length < 4096)
    }

    @Test
    fun `finishing should reuse an immutable snapshot and refuse new owners`() {
        val budget = assertNotNull(MirUseBudget.create())
        assertNotNull(budget.owner(3L, 3, 0, 2, 0, 1))
        val first = budget.finish()

        val second = budget.finish()
        val owner = budget.owner(4L, 3, 0, 2, 0, 1)

        assertSame(first, second)
        assertNull(owner)
    }

    @Test
    fun `merging repeated invocation stats should preserve one diagnostic snapshot`() {
        val budget = assertNotNull(MirUseBudget.create())
        assertNotNull(budget.owner(3L, 3, 0, 2, 0, 1))
        val snapshot = budget.finish()
        val stats = LpStats(mirUse = snapshot)

        val merged = stats.mergedWith(stats)

        assertSame(snapshot, merged.mirUse)
    }

    @Test
    fun `merging foreign invocation stats should refuse additional diagnostic data`() {
        val first = assertNotNull(MirUseBudget.create()).finish()
        val second = assertNotNull(MirUseBudget.create()).finish()

        val merged = assertNotNull(LpStats(mirUse = first).mergedWith(LpStats(mirUse = second)).mirUse)

        assertEquals(first.invocationId, merged.invocationId)
        assertSame(first.encoded, merged.encoded)
        assertFalse(merged.complete)
        assertTrue(merged.foreignObservationsDiscarded)
    }
}
