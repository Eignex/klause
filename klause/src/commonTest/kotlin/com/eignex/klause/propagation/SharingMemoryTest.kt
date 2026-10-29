package com.eignex.klause.propagation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearPropagator
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.klause.util.SharingMemoryStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharingMemoryTest {
    @Test
    fun `tiny allowances stop observations before completing either learned store`() {
        for (native in listOf(false, true)) for (allowance in listOf(0L, 1L, 7L)) {
            val session = PropagationSession(Problem(32, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(62, 63)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            repeat(8) { session.addLearnedClause(Clause(intArrayOf(it * 2, it * 2 + 2)), 2) }
            val before = session.work

            val sample = session.sharingMemory(maxWork = allowance)

            assertEquals(SharingMemoryStatus.PARTIAL, sample.status)
            assertEquals(allowance, sample.work)
            assertEquals(allowance, session.work - before)
            assertNull(sample.completeValues)
            assertEquals(8, session.learnedClauseCount)
        }
    }

    @Test
    fun `cancellation sees each charged diagnostic visit before the next visit`() {
        for (native in listOf(false, true)) for (allowance in listOf(5L, 80L)) {
            var limit = Long.MAX_VALUE
            lateinit var session: PropagationSession
            session = PropagationSession(Problem(32, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(62, 63)))), nativeSat = native,
                cancellation = Cancellation { limit != Long.MAX_VALUE && session.work >= limit })
            assertEquals(native, session.usesNativeSat)
            repeat(8) { session.addLearnedClause(Clause(intArrayOf(it * 2, it * 2 + 2)), 2) }
            val before = session.work
            limit = before + allowance

            val sample = session.sharingMemory(maxWork = 10_000L)

            assertEquals(SharingMemoryStatus.PARTIAL, sample.status)
            assertEquals(allowance, sample.work)
            assertEquals(allowance, session.work - before)
            assertNull(sample.completeValues)
        }
    }

    @Test
    fun `scratch exhaustion preserves an active reason through compaction`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(6, 7)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.pinBool(0, false)

            val sample = session.sharingMemory(maxScratchEntries = 0)
            session.forgetLearnedClauses { _, _ -> false }

            assertEquals(SharingMemoryStatus.PARTIAL, sample.status)
            assertEquals(0, sample.scratchEntries)
            assertEquals(true, session.boolValue(1))
            assertEquals(1, session.learnedClauseCount)
            assertNotNull(session.boolReasonClause(1))
        }
    }

    @Test
    fun `complete snapshots expose protected reasons on both stores`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(6, 7)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.pinBool(0, false)

            val sample = session.sharingMemory()

            assertEquals(SharingMemoryStatus.COMPLETE, sample.status)
            val values = assertNotNull(sample.completeValues)
            assertEquals(1L, values.getValue("reasonProtectedObjects"))
            assertTrue(values.getValue("reasonPayloadBytes") >= 4L)
            assertEquals(1L, values.getValue("learnedObjects"))
        }
    }

    @Test
    fun `partial observation preserves an integer reason saved in undo history`() {
        val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 10)),
            arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))))
        session.addLearnedClause(Clause(intArrayOf(0, 1)), 4)
        val row = requireNotNull(IntegerConflictRow.source(
            LinearPropagator(intArrayOf(), intArrayOf(0), longArrayOf(1), intArrayOf(0), LinearOp.LE, 8),
            useLo = true))
        session.addLearnedIntegerRow(row, 2)
        session.pinIntAtMost(0, 4)
        val complete = session.sharingMemory()
        assertEquals(SharingMemoryStatus.COMPLETE, complete.status)
        assertEquals(1L, assertNotNull(complete.completeValues).getValue("reasonProtectedObjects"))

        val sample = session.sharingMemory(maxScratchEntries = 1)
        session.forgetLearnedClauses { _, _ -> false }
        session.popLast()

        assertEquals(SharingMemoryStatus.PARTIAL, sample.status)
        assertTrue(sample.scratchEntries <= 1)
        assertEquals(IntDomain(0, 8), session.intDomain(0))
        assertIs<LearnedIntegerPropagator>(session.explanationState.factorAt(1))
    }

    @Test
    fun `shared search observation uses the remaining native allowance`() {
        val session = PropagationSession(Problem(0, 0, emptyArray(), emptyList()))
        val native = session.sharingMemory()
        val shared = SearchSession(emptyList())
        session.sharedLearningMemory = shared::retainedLearningMemory
        val before = session.work

        val sample = session.sharingMemory(maxWork = native.work + 2L)

        assertEquals(SharingMemoryStatus.PARTIAL, sample.status)
        assertEquals(native.work + 2L, sample.work)
        assertEquals(sample.work, session.work - before)
        assertNull(sample.completeValues)
    }

    @Test
    fun `general permanent clauses should protect their metadata references and watched postings`() {
        for (permanent in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(), emptyList()))
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2, permanent = permanent)
            val state = session.explanationState
            val columns = listOf(state.learned.stages, state.learned.lbds, state.learned.permanent,
                state.learned.tier, state.learned.usedFlags, state.learned.origins,
                state.learned.units, state.learned.pendingAttach)
            var dependencies = columns.sumOf { it.backingData.size.toLong() * Int.SIZE_BYTES } +
                state.watches.pos.primitiveCapacityBytes
            for (literal in listOf(0, 2)) {
                dependencies += (state.watches.byLit[literal].backingData.size.toLong() +
                    state.watches.blockersByLit[literal].backingData.size) * Int.SIZE_BYTES
            }
            val sample = SharingMemoryObservation(1_000_000L, 4096, { false }, {})
            val before = assertNotNull(sample.snapshot { state.sharingMemory(sample) }.completeValues)

            val after = assertNotNull(sample.snapshot {
                for (column in columns) {
                    sample.owned(column.backingData, column.backingData.size.toLong() * Int.SIZE_BYTES, true)
                }
                sample.ownedReferences(state.learned.store, state.learned.size.toLong(), true)
                sample.ownedReferences(state.refPayloadStore, state.refPayloadStore.size.toLong(), true)
                sample.owned(state.watches.pos, state.watches.pos.primitiveCapacityBytes, true)
                for (literal in listOf(0, 2)) {
                    for (posting in listOf(state.watches.byLit[literal], state.watches.blockersByLit[literal])) {
                        sample.owned(posting.backingData, posting.backingData.size.toLong() * Int.SIZE_BYTES, true)
                    }
                }
            }.completeValues)

            assertEquals(before.getValue("ownedPrimitiveBytes"), after.getValue("ownedPrimitiveBytes"))
            assertEquals(before.getValue("ownedReferenceSlots"), after.getValue("ownedReferenceSlots"))
            assertEquals(if (permanent) 0L else dependencies,
                after.getValue("protectedPrimitiveBytes") - before.getValue("protectedPrimitiveBytes"))
            assertEquals(if (permanent) 0L else state.learned.size.toLong() + state.refPayloadStore.size,
                after.getValue("protectedReferenceSlots") - before.getValue("protectedReferenceSlots"))
        }
    }

    @Test
    fun `native live reasons should protect metadata and watched posting capacity`() {
        val source = Problem(4, 0, emptyArray(), arrayOf(Clause(intArrayOf(6, 7))))
        val session = PropagationSession(source, nativeSat = true)
        val state = session.explanationState
        val native = assertNotNull(state.nativeEngine)
        val sample = SharingMemoryObservation(1_000_000L, 4096, { false }, {})
        val baseline = assertNotNull(sample.snapshot {
            native.sharingMemory(sample, sample.seen())
        }.completeValues)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
        session.pinBool(0, false)
        val reason = state.boolReason[1] - state.baseFactorCount
        assertEquals(0, reason)
        val before = assertNotNull(sample.snapshot {
            native.sharingMemory(sample, sample.seen())
        }.completeValues)

        val after = assertNotNull(sample.snapshot {
            val protected = sample.seen<Int>()
            sample.retain(protected, reason)
            native.sharingMemory(sample, protected)
        }.completeValues)

        val dependencies = before.getValue("learnedMetadataCapacityBytes") +
            before.getValue("learnedArenaCapacityBytes") + before.getValue("sessionWatchCapacityBytes") -
            baseline.getValue("sessionWatchCapacityBytes")
        assertEquals(before.getValue("ownedPrimitiveBytes"), after.getValue("ownedPrimitiveBytes"))
        assertEquals(dependencies,
            after.getValue("protectedPrimitiveBytes") - before.getValue("protectedPrimitiveBytes"))
    }

    @Test
    fun `unrelated learned watcher postings should remain outside the protected floor`() {
        val session = PropagationSession(Problem(4, 0, emptyArray(), emptyList()))
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 2, permanent = true)
        session.addLearnedClause(Clause(intArrayOf(4, 6)), 2)
        val state = session.explanationState
        val posting = state.watches.byLit[4].backingData
        val blockers = state.watches.blockersByLit[4].backingData
        val sample = SharingMemoryObservation(1_000_000L, 4096, { false }, {})
        val before = assertNotNull(sample.snapshot { state.sharingMemory(sample) }.completeValues)

        val after = assertNotNull(sample.snapshot {
            sample.owned(posting, posting.size.toLong() * Int.SIZE_BYTES, true)
            sample.owned(blockers, blockers.size.toLong() * Int.SIZE_BYTES, true)
        }.completeValues)

        assertEquals((posting.size.toLong() + blockers.size) * Int.SIZE_BYTES,
            after.getValue("protectedPrimitiveBytes") - before.getValue("protectedPrimitiveBytes"))
    }

    @Test
    fun `integer event dependencies should be protected only by a retained integer row`() {
        for (active in listOf(false, true)) {
            val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 10)),
                arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 10))))
            session.addLearnedClause(Clause(intArrayOf(0, 1)), 2, permanent = true)
            val row = requireNotNull(IntegerConflictRow.source(
                LinearPropagator(intArrayOf(), intArrayOf(0), longArrayOf(1), intArrayOf(0), LinearOp.LE, 8),
                useLo = true, guard = 0))
            session.addLearnedIntegerRow(row, 2)
            if (active) session.pinBool(0, true)
            val state = session.explanationState
            val rows = assertNotNull(state.learned.integerRows)
            val overflow = assertNotNull(state.intEvents.overflow)
            var dependencies = overflow.primitiveCapacityBytes
            for (value in overflow.valuesInternal) {
                if (value != null) dependencies += (value as IntArrayList).backingData.size.toLong() * Int.SIZE_BYTES
            }
            val sample = SharingMemoryObservation(1_000_000L, 4096, { false }, {})
            val before = assertNotNull(sample.snapshot { state.sharingMemory(sample) }.completeValues)

            val after = assertNotNull(sample.snapshot {
                sample.ownedReferences(rows, rows.size.toLong(), true)
                sample.owned(overflow, overflow.primitiveCapacityBytes, true)
                sample.ownedReferences(overflow, overflow.referenceCapacity.toLong(), true)
                for (value in overflow.valuesInternal) {
                    sample.step()
                    if (value != null) {
                        val array = (value as IntArrayList).backingData
                        sample.owned(array, array.size.toLong() * Int.SIZE_BYTES, true)
                    }
                }
            }.completeValues)

            assertEquals(before.getValue("ownedPrimitiveBytes"), after.getValue("ownedPrimitiveBytes"))
            assertEquals(before.getValue("ownedReferenceSlots"), after.getValue("ownedReferenceSlots"))
            assertEquals(if (active) 0L else dependencies,
                after.getValue("protectedPrimitiveBytes") - before.getValue("protectedPrimitiveBytes"))
            assertEquals(if (active) 0L else rows.size.toLong() + overflow.referenceCapacity,
                after.getValue("protectedReferenceSlots") - before.getValue("protectedReferenceSlots"))
        }
    }
}
