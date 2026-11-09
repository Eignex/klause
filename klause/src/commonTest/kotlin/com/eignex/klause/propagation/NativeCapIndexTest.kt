package com.eignex.klause.propagation

import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.util.collections.IntArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeCapIndexTest {
    @Test
    fun `page reservation should catch a growing candidate window within bounded grants`() {
        for (initialRows in listOf(4_000, 20_000)) {
            val candidates = IntArrayList()
            repeat(initialRows) { candidates.add(it) }
            val index = NativeCapIndex(candidates)
            var completed = false
            repeat(8) {
                if (!completed) {
                    repeat(128) { candidates.add(candidates.size) }
                    var charged = 0L
                    val budget = ReclamationPreparation(65_536L, 1_048_576L, { false }, { charged += it })
                    try {
                        index.reservePages(budget)
                        completed = true
                    } catch (_: ReclamationPreparation.Deferred) { }
                    assertTrue(charged <= 65_536L)
                    assertEquals(charged, budget.work)
                }
            }

            assertTrue(completed, "initialRows=$initialRows")
            assertEquals((0 until candidates.size).toList(), candidates.toIntArray().toList())
        }
    }

    @Test
    fun `completed page reservation should retain the scratch limit for another page`() {
        val candidates = IntArrayList()
        candidates.add(0)
        val index = NativeCapIndex(candidates)
        val before = index.modeledBytes
        val budget = ReclamationPreparation(65_536L, NATIVE_CAP_PAGE_BYTES, { false }, {})

        assertFailsWith<ReclamationPreparation.Deferred> { index.reservePages(budget) }

        assertEquals(NATIVE_CAP_PAGE_BYTES, index.modeledBytes - before)
        assertTrue(budget.work <= 65_536L)
    }

    @Test
    fun `cancellation before page publication should retain reservation progress for a fresh grant`() {
        for (stop in 1..4) {
            val candidates = IntArrayList()
            candidates.add(0)
            val index = NativeCapIndex(candidates)
            val before = index.modeledBytes
            var polls = 0
            val cancelled = ReclamationPreparation(65_536L, 1_048_576L, { ++polls == stop }, {})

            assertFailsWith<ReclamationPreparation.Deferred> { index.reservePages(cancelled) }
            assertEquals(before, index.modeledBytes, "stop=$stop")
            val resumed = ReclamationPreparation(65_536L, NATIVE_CAP_PAGE_BYTES, { false }, {})
            assertFailsWith<ReclamationPreparation.Deferred> { index.reservePages(resumed) }

            assertEquals(NATIVE_CAP_PAGE_BYTES, index.modeledBytes - before, "stop=$stop")
        }
    }

    @Test
    fun `completed untracked retirement preparation should not repay its work`() {
        val rows = Rows()
        val first = ReclamationPreparation(1L, 64L, { false }, {})
        rows.index.prepareUntrackedRetirement(7, first)
        val next = ReclamationPreparation(0L, 64L, { false }, {})

        rows.index.prepareUntrackedRetirement(7, next)

        assertEquals(1L, first.work)
        assertEquals(0L, next.work)
        assertEquals(7, rows.index.retirementBaseline)
    }

    @Test
    fun `cancellation at untracked retirement debit should preserve the baseline`() {
        val rows = Rows()
        var charged = 0L
        val budget = ReclamationPreparation(64L, 64L, { charged >= 1L }, { charged += it })

        assertFailsWith<ReclamationPreparation.Deferred> { rows.index.prepareUntrackedRetirement(7, budget) }

        assertEquals(0, rows.index.retirementBaseline)
        assertEquals(1L, budget.work)
    }

    @Test
    fun `initialization should capture retirements completed during reservation`() {
        val rows = Rows()
        rows.add(9)
        rows.budget = ReclamationPreparation(64L, 512L, { false }, {})
        try { rows.index.advance(0, rows.configuration, rows.budget, rows.policy) }
        catch (_: ReclamationPreparation.Deferred) { }
        rows.retirements = 7

        rows.finish(0)

        assertEquals(7, rows.index.retirementBaseline)
    }

    @Test
    fun `reset should discard the preceding retirement baseline`() {
        val rows = Rows()
        rows.add(9)
        rows.retirements = 7
        rows.finish(0)

        rows.index.reset()

        assertEquals(0, rows.index.retirementBaseline)
    }

    @Test
    fun `incremental windows should retain the same LBD and handle priorities`() {
        val rows = Rows()
        repeat(6) { rows.add(9 - it) }

        rows.finish(3)

        assertEquals(listOf(3, 4, 5), rows.survivors())
        rows.add(4)
        rows.add(5)
        rows.finish(3)
        assertEquals(listOf(4, 5, 6), rows.survivors())
        assertContentEquals(intArrayOf(1, 1, 1, 1, 1, 1, 1, 1), rows.calls.take(8).toIntArray())
    }

    @Test
    fun `idle MID demotion should survive its own epoch`() {
        val rows = Rows()
        rows.add(6, ClauseTier.MID)
        rows.add(8)

        rows.finish(0)

        assertEquals(listOf(0), rows.survivors())
        assertEquals(ClauseTier.LOCAL, rows.tiers[0])
        rows.finish(0)
        assertTrue(rows.survivors().isEmpty())
        assertEquals(1, rows.calls[0])
    }

    @Test
    fun `used LOCAL rows should promote then age once in each window`() {
        val rows = Rows()
        rows.add(9)
        rows.used[0] = true

        rows.finish(0)
        assertEquals(ClauseTier.MID, rows.tiers[0])
        assertFalse(rows.used[0])
        assertEquals(listOf(0), rows.survivors())
        rows.finish(0)
        assertEquals(ClauseTier.LOCAL, rows.tiers[0])
        assertEquals(listOf(0), rows.survivors())
        rows.finish(0)
        assertTrue(rows.survivors().isEmpty())
        assertEquals(2, rows.calls[0])
    }

    @Test
    fun `overlapping use and aging entries should classify a row only once`() {
        val rows = Rows()
        rows.add(9, ClauseTier.MID)
        rows.used[0] = true
        rows.finish(0)
        rows.use(0)

        rows.finish(0)

        assertEquals(2, rows.calls[0])
        assertEquals(ClauseTier.MID, rows.tiers[0])
        assertFalse(rows.used[0])
    }

    @Test
    fun `a new use after completed classification should protect eviction and survive suspension`() {
        val rows = Rows()
        repeat(4) { rows.add(9 - it) }
        rows.keepHook = { id -> if (id == 0) { rows.use(0); rows.cancelled = true } }
        rows.attempt(1)
        assertEquals(1, rows.calls[0])
        rows.keepHook = {}
        rows.cancelled = false

        rows.finish(1)

        assertTrue(rows.alive[0])
        assertEquals(1, rows.calls[0])
        rows.finish(0)
        assertEquals(ClauseTier.MID, rows.tiers[0])
        assertEquals(2, rows.calls[0])
    }

    @Test
    fun `cancellation at funded boundaries should preserve completed classifications`() {
        for (stop in 0L..720L step 12) {
            val rows = Rows()
            repeat(6) { rows.add(9 - it) }
            var charged = 0L
            rows.budget = ReclamationPreparation(100_000L, 1_048_576L, { charged >= stop }, { charged += it })
            try { rows.index.advance(2, rows.configuration, rows.budget, rows.policy) }
            catch (_: ReclamationPreparation.Deferred) { }

            rows.finish(2)

            assertEquals(listOf(4, 5), rows.survivors(), "stop=$stop")
            assertTrue(rows.calls.take(6).all { it == 1 }, "stop=$stop")
        }
    }

    @Test
    fun `admissions during suspended heap removal should retain every handle`() {
        val rows = Rows()
        repeat(6) { rows.add(10 - it) }
        rows.retireHook = { rows.cancelled = true }
        rows.attempt(2)
        rows.add(3)
        rows.add(4)
        rows.retireHook = {}
        rows.cancelled = false

        rows.finish(2)

        assertEquals(listOf(6, 7), rows.survivors())
        assertEquals(rows.survivors(), rows.candidates.toIntArray().sorted())
    }

    @Test
    fun `external retirement below cap should clean handles without aging tiers`() {
        val rows = Rows()
        repeat(4) { rows.add(6, ClauseTier.MID) }
        rows.used.fill(true)
        rows.finish(0)
        val before = rows.calls.copyOf()
        rows.alive[2] = false
        rows.index.invalidateRetiredHandles()

        rows.finish(4)

        assertContentEquals(before, rows.calls)
        assertEquals(listOf(0, 1, 3), rows.candidates.toIntArray().sorted())
        assertEquals(ClauseTier.MID, rows.tiers[0])
    }

    @Test
    fun `released metadata keys should not hide a worse survivor during suspended sifting`() {
        val rows = Rows()
        for (key in listOf(20, 10, 5, 9, 8, 4, 3, 6)) rows.add(key)
        rows.finish(8)
        rows.retireHook = { rows.cancelled = true }
        rows.attempt(7)
        rows.alive[1] = false
        rows.keys[1] = 0
        rows.index.invalidateRetiredHandles()
        rows.retireHook = {}
        rows.cancelled = false
        var completed = false
        repeat(64) {
            if (!completed) {
                rows.budget = ReclamationPreparation(64L, 512L, { false }, {})
                try {
                    completed = rows.index.advance(7, rows.configuration, rows.budget, rows.policy) !=
                        NativeCapIndex.Completion.None
                } catch (_: ReclamationPreparation.Deferred) { }
                assertTrue(rows.budget.work <= 64L)
            }
        }
        assertTrue(completed)

        rows.finish(5)

        assertFalse(rows.alive[3])
        assertTrue(rows.alive[7])
        assertEquals(listOf(2, 4, 5, 6, 7), rows.survivors())
        assertTrue(rows.calls.take(8).all { it == 1 })
    }

    @Test
    fun `a protected root should retry under a later cap window`() {
        val rows = Rows()
        repeat(4) { rows.add(9 - it) }
        rows.protected[0] = true
        rows.finish(1)
        assertEquals(listOf(0), rows.survivors())
        rows.protected[0] = false

        rows.finish(0)

        assertTrue(rows.survivors().isEmpty())
    }

    @Test
    fun `explicit policy changes should rescan unchanged rows`() {
        val rows = Rows()
        repeat(4) { rows.add(9 - it) }
        rows.finish(3)
        rows.tiers[1] = ClauseTier.CORE
        rows.index.requestRescan()
        rows.add(4)

        rows.finish(2)

        assertTrue(rows.alive[1])
        assertEquals(ClauseTier.CORE, rows.tiers[1])
        assertEquals(2, rows.calls[1])
    }

    @Test
    fun `aborting at any funded boundary should restore a complete candidate set`() {
        for (stop in 320L..720L step 8) {
            val rows = Rows()
            repeat(6) { rows.add(9 - it) }
            var charged = 0L
            rows.budget = ReclamationPreparation(100_000L, 1_048_576L, { charged >= stop }, { charged += it })
            try { rows.index.advance(2, rows.configuration, rows.budget, rows.policy) }
            catch (_: ReclamationPreparation.Deferred) { }
            rows.add(3)
            rows.budget = ReclamationPreparation(100_000L, 1_048_576L, { false }, {})

            assertEquals(NativeCapIndex.Completion.Aborted, rows.index.abort(rows.budget, rows.policy))

            assertEquals(rows.survivors(), rows.candidates.toIntArray().sorted())
            assertTrue(rows.classified.none { it })
        }
    }

    @Test
    fun `all retired rows should release every auxiliary page`() {
        val rows = Rows()
        repeat(16) { rows.add(9) }
        rows.finish(8)
        val populated = rows.index.modeledBytes
        val occupied = SharingMemoryObservation(10_000L, 1024, { false }, {})
        val occupiedValues = assertNotNull(occupied.snapshot {
            rows.index.sharingMemory(occupied, false)
        }.completeValues)
        assertEquals(rows.index.primitiveBytes, occupiedValues.getValue("ownedPrimitiveBytes"))
        assertEquals(populated, occupiedValues.getValue("ownedPrimitiveBytes") +
            occupiedValues.getValue("ownedReferenceSlots") * Long.SIZE_BYTES)

        rows.finish(0)

        assertTrue(rows.index.modeledBytes < populated)
        assertEquals(NativeCapIndex.CREATION_BYTES, rows.index.modeledBytes)
        assertFalse(rows.index.readyForAdmissions)
        val sample = SharingMemoryObservation(10_000L, 1024, { false }, {})
        val snapshot = sample.snapshot { rows.index.sharingMemory(sample, false) }
        val values = assertNotNull(snapshot.completeValues)
        assertEquals(rows.index.primitiveBytes, values.getValue("ownedPrimitiveBytes"))
        assertEquals(rows.index.modeledBytes,
            values.getValue("ownedPrimitiveBytes") + values.getValue("ownedReferenceSlots") * Long.SIZE_BYTES)
    }

    @Test
    fun `reset should release private markers and admit remapped handles afresh`() {
        val rows = Rows()
        repeat(4) { rows.add(9 - it) }
        rows.finish(2)

        rows.index.reset()
        rows.finish(0)

        assertTrue(rows.survivors().isEmpty())
        assertEquals(NativeCapIndex.CREATION_BYTES, rows.index.modeledBytes)
    }

    @Test
    fun `untiered retirement should release obsolete MID aging pages`() {
        val rows = Rows()
        rows.add(6, ClauseTier.MID)
        rows.used[0] = true
        rows.finish(0)
        rows.configuration = NativeCapConfiguration(false, 2, 6)

        rows.finish(0)

        assertTrue(rows.survivors().isEmpty())
        assertEquals(NativeCapIndex.CREATION_BYTES, rows.index.modeledBytes)
    }

    @Test
    fun `late cleared use should not retain a retired queue handle`() {
        val rows = Rows()
        rows.add(9)
        rows.keepHook = { rows.cancelled = true }
        rows.attempt(0)
        rows.keepHook = {}
        rows.cancelled = false
        rows.use(0)
        rows.used[0] = false
        rows.index.requestRescan()

        rows.finish(0)

        assertTrue(rows.survivors().isEmpty())
        assertEquals(1, rows.calls[0])
        assertEquals(NativeCapIndex.CREATION_BYTES, rows.index.modeledBytes)
    }

    @Test
    fun `bootstrap and tiny continuation grants should preserve exact survivors`() {
        val rows = Rows()
        repeat(8) { rows.add(9 - it / 2) }
        var completed = false
        repeat(128) {
            if (!completed) {
                val work = if (rows.index.bootstrapping) 2048L else 64L
                rows.budget = ReclamationPreparation(work, 512L, { false }, {})
                try {
                    completed = rows.index.advance(2, rows.configuration, rows.budget, rows.policy) !=
                        NativeCapIndex.Completion.None
                }
                catch (_: ReclamationPreparation.Deferred) { }
                assertTrue(rows.budget.work <= work)
            }
        }

        assertTrue(completed)
        assertEquals(listOf(6, 7), rows.survivors())
        assertTrue(rows.calls.take(8).all { it == 1 })
    }

    @Test
    fun `retirement during bootstrap should finish below cap without aging or retaining dead handles`() {
        val rows = Rows()
        repeat(8) { rows.add(6, ClauseTier.MID) }
        rows.budget = ReclamationPreparation(1024L, 512L, { false }, {})
        try { rows.index.advance(0, rows.configuration, rows.budget, rows.policy) }
        catch (_: ReclamationPreparation.Deferred) { }
        assertTrue(rows.index.bootstrapping)
        rows.alive.fill(false)
        rows.index.invalidateRetiredHandles()

        rows.finish(8)

        assertTrue(rows.candidates.size == 0)
        assertTrue(rows.calls.all { it == 0 })
        assertEquals(NativeCapIndex.CREATION_BYTES, rows.index.modeledBytes)
    }

    @Test
    fun `a live admission cleaned below cap should classify in its first overfull epoch`() {
        val rows = Rows()
        repeat(4) { rows.add(6, ClauseTier.MID) }
        rows.budget = ReclamationPreparation(1024L, 512L, { false }, {})
        try { rows.index.advance(0, rows.configuration, rows.budget, rows.policy) }
        catch (_: ReclamationPreparation.Deferred) { }
        rows.alive[1] = false
        rows.index.invalidateRetiredHandles()
        rows.finish(4)
        assertEquals(ClauseTier.MID, rows.tiers[0])
        assertTrue(rows.calls.all { it == 0 })

        rows.finish(0)

        assertEquals(1, rows.calls[0])
        assertEquals(ClauseTier.LOCAL, rows.tiers[0])
        assertEquals(listOf(0, 2, 3), rows.survivors())
    }

    @Test
    fun `admission after an empty window should not reserve orphaned queue pages`() {
        val rows = Rows()
        rows.add(9)
        rows.finish(0)
        val empty = rows.index.modeledBytes

        rows.add(8)

        assertEquals(empty, rows.index.modeledBytes)
        rows.finish(0)
        assertTrue(rows.survivors().isEmpty())
        assertEquals(empty, rows.index.modeledBytes)
    }

    @Test
    fun `retirement after reset below cap should not leave a pending epoch`() {
        val rows = Rows()
        repeat(2) { rows.add(9) }
        rows.finish(1)
        rows.index.reset()
        rows.alive.fill(false)
        rows.index.invalidateRetiredHandles()

        rows.finish(1)

        assertFalse(rows.index.hasPending)
        assertEquals(NativeCapIndex.CREATION_BYTES, rows.index.modeledBytes)
    }

    private class Rows {
        val candidates = IntArrayList()
        val index = NativeCapIndex(candidates)
        val alive = BooleanArray(32)
        val used = BooleanArray(32)
        val protected = BooleanArray(32)
        val classified = BooleanArray(32)
        val kept = BooleanArray(32)
        val keys = IntArray(32)
        val calls = IntArray(32)
        val tiers = Array(32) { ClauseTier.LOCAL }
        var configuration = NativeCapConfiguration(true, 2, 6)
        var count = 0
        var retirements = 0
        var cancelled = false
        var budget = ReclamationPreparation(100_000L, 1_048_576L, { cancelled }, {})
        var keepHook: (Int) -> Unit = {}
        var retireHook: () -> Unit = {}
        val policy = object : NativeIndexedCapPolicy {
            override val activeCount: Int get() = alive.count { it }
            override val retirementCount: Int get() = retirements
            override fun alive(index: Int): Boolean = alive[index]
            override fun lbd(index: Int): Int = keys[index]
            override fun prepare(index: Int): Boolean { budget.visit(36L); return true }
            override fun visited() = Unit
            override fun keep(index: Int): Boolean {
                calls[index]++
                if (!configuration.tiered) {
                    keepHook(index)
                    return keys[index] <= configuration.glue || protected[index]
                }
                val before = tiers[index]
                val recent = used[index]
                tiers[index] = when (before) {
                    ClauseTier.CORE -> ClauseTier.CORE
                    ClauseTier.MID, ClauseTier.LOCAL -> if (recent) ClauseTier.MID else ClauseTier.LOCAL
                    ClauseTier.UNSET -> error("explicit test tier required")
                }
                used[index] = false
                keepHook(index)
                return before != ClauseTier.LOCAL || recent || protected[index]
            }
            override fun retire(index: Int): Boolean {
                if (kept[index] || protected[index] || (configuration.tiered &&
                    (used[index] || tiers[index] != ClauseTier.LOCAL))) return false
                budget.visit(24L)
                budget.visit(0L)
                alive[index] = false
                retireHook()
                return true
            }
            override fun classified(index: Int): Boolean = classified[index]
            override fun markClassification(index: Int, keep: Boolean): Boolean {
                budget.visit(0L)
                classified[index] = true
                kept[index] = keep
                return tiers[index] == ClauseTier.MID
            }
            override fun clearClassification(index: Int) {
                budget.visit(0L)
                classified[index] = false
                kept[index] = false
            }
        }

        fun add(key: Int, tier: ClauseTier = ClauseTier.LOCAL) {
            repeat(index.admissionPages(candidates.size + 1).toInt()) { index.reserveAdmission() }
            keys[count] = key
            tiers[count] = tier
            alive[count] = true
            candidates.add(count++)
        }

        fun use(id: Int) {
            if (!used[id] && index.recordsUses) index.recordUse(id)
            used[id] = true
        }

        fun survivors(): List<Int> = (0 until count).filter { alive[it] }

        fun attempt(cap: Int): NativeCapIndex.Completion {
            budget = ReclamationPreparation(100_000L, 1_048_576L, { cancelled }, {})
            return try { index.advance(cap, configuration, budget, policy) }
            catch (_: ReclamationPreparation.Deferred) { NativeCapIndex.Completion.None }
        }

        fun finish(cap: Int) {
            var complete = false
            repeat(32) {
                if (!complete) complete = attempt(cap) != NativeCapIndex.Completion.None || !index.hasPending
            }
            assertTrue(complete)
            assertFalse(index.hasPending)
        }
    }
}
