package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.LearnedRetirementStatus
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RootLearnedRetirementTest {
    @Test
    fun `an empty controller should remain in the owned memory observation`() {
        val retirement = RootLearnedRetirement()
        val owner = Any()
        val sample = SharingMemoryObservation(1024L, 128, { false }, {})

        val memory = sample.snapshot { retirement.retainedMemory(sample, owner) }

        val values = assertNotNull(memory.completeValues)
        assertEquals(12L, values["ownedPrimitiveBytes"])
        assertEquals(3L, values["ownedReferenceSlots"])
        assertEquals(0L, values["protectedPrimitiveBytes"])
        assertEquals(0L, values["protectedReferenceSlots"])
    }

    @Test
    fun `captured metadata should remain protected until cancellation completes`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(4, 6)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            val retirement = RootLearnedRetirement()
            val owner = Any()
            val spending = InprocessingSpending(InprocessingKind.Root)
            val params = BacktrackParams(inprocessingRootWork = 256L)
            spending.run(session, params, null) { budget ->
                assertEquals(LearnedRetirementStatus.Captured,
                    retirement.capture(session, 0, session.learnedRetirementGeneration, params, budget))
            }
            retirement.abandon()
            val before = SharingMemoryObservation(1024L, 128, { false }, {})
            val retained = assertNotNull(before.snapshot {
                retirement.retainedMemory(before, owner)
            }.completeValues)
            assertEquals(12L, retained["protectedPrimitiveBytes"])
            assertEquals(3L, retained["protectedReferenceSlots"])

            spending.run(session, params, null) { budget -> retirement.prepare(session, params, budget) }
            val after = SharingMemoryObservation(1024L, 128, { false }, {})
            val released = assertNotNull(after.snapshot {
                retirement.retainedMemory(after, owner)
            }.completeValues)

            assertEquals(12L, released["ownedPrimitiveBytes"])
            assertEquals(3L, released["ownedReferenceSlots"])
            assertEquals(0L, released["protectedPrimitiveBytes"])
            assertEquals(0L, released["protectedReferenceSlots"])
        }
    }

    @Test
    fun `an earlier publication should not authorize a later selection`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(4, 6)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            val retirement = RootLearnedRetirement()
            val spending = InprocessingSpending(InprocessingKind.Root)
            val params = BacktrackParams(inprocessingRootWork = 256L)
            spending.run(session, params, null) { budget ->
                assertEquals(LearnedRetirementStatus.Captured,
                    retirement.capture(session, 0, session.learnedRetirementGeneration, params, budget))
            }
            retirement.rootPublished()
            retirement.markSelected()

            spending.run(session, params, null) { budget ->
                assertFalse(retirement.prepare(session, params, budget))
                retirement.advance(session, params, budget)
            }

            assertTrue(session.isLearnedClause(0))
            retirement.rootPublished()
            spending.run(session, params, null) { budget ->
                assertTrue(retirement.prepare(session, params, budget))
                retirement.advance(session, params, budget)
            }
            assertFalse(session.isLearnedClause(0))
        }
    }

    @Test
    fun `abandoning an unpublished selection should cancel its owner without retirement`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(4, 6)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            val retirement = RootLearnedRetirement()
            val spending = InprocessingSpending(InprocessingKind.Root)
            val params = BacktrackParams(inprocessingRootWork = 256L)
            spending.run(session, params, null) { budget ->
                assertEquals(LearnedRetirementStatus.Captured,
                    retirement.capture(session, 0, session.learnedRetirementGeneration, params, budget))
            }
            retirement.markSelected()

            retirement.abandon()
            repeat(3) {
                spending.run(session, params, null) { budget ->
                    retirement.prepare(session, params, budget)
                    retirement.advance(session, params, budget)
                }
            }

            assertTrue(session.isLearnedClause(0))
            assertFalse(retirement.hasCapture)
            assertFalse(session.hasLearnedRetirementContinuation)
            val ownership = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
            assertEquals(0L, ownership["selectedRetirementRequests"])
        }
    }

    @Test
    fun `terminal acknowledgement should resume after caller metadata is released`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(4, 6)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            val retirement = RootLearnedRetirement()
            val spending = InprocessingSpending(InprocessingKind.Root)
            val params = BacktrackParams(inprocessingRootWork = 256L)
            spending.run(session, params, null) { budget ->
                assertEquals(LearnedRetirementStatus.Captured,
                    retirement.capture(session, 0, session.learnedRetirementGeneration, params, budget))
            }
            retirement.markSelected()
            retirement.rootPublished()
            val limited = params.copy(inprocessingRootWork = 81L)
            spending.run(session, limited, null) { budget ->
                retirement.prepare(session, limited, budget)
                retirement.advance(session, limited, budget)
            }
            assertFalse(session.isLearnedClause(0))
            assertTrue(session.hasLearnedRetirementContinuation)

            retirement.abandon()
            repeat(3) {
                spending.run(session, params, null) { budget -> retirement.advance(session, params, budget) }
            }

            assertFalse(session.hasLearnedRetirementContinuation)
            val ownership = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
            assertEquals(0L, ownership["selectedRetirementRequests"])
        }
    }

    @Test
    fun `protected continuation should wait through observer work`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(1)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            val retirement = RootLearnedRetirement()
            val spending = InprocessingSpending(InprocessingKind.Root)
            val params = BacktrackParams(inprocessingRootWork = 256L)
            spending.run(session, params, null) { budget ->
                assertEquals(LearnedRetirementStatus.Captured,
                    retirement.capture(session, 0, session.learnedRetirementGeneration, params, budget))
            }
            retirement.markSelected()
            retirement.rootPublished()
            repeat(2) {
                spending.run(session, params, null) { budget ->
                    retirement.prepare(session, params, budget)
                    retirement.advance(session, params, budget)
                    retirement.finishSlice(session)
                }
            }
            assertFalse(retirement.hasContinuation(session))

            assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

            assertFalse(retirement.hasContinuation(session))
            assertTrue(session.hasLearnedRetirementContinuation)
            assertTrue(session.isLearnedClause(0))
            assertEquals(true, session.boolValue(1))
        }
    }

    @Test
    fun `a protected owner should permit a later eligible retirement`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(1)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 9)
            val retirement = RootLearnedRetirement()
            val spending = InprocessingSpending(InprocessingKind.Root)
            val params = BacktrackParams(inprocessingRootWork = 256L)
            for (index in 0..1) {
                spending.run(session, params, null) { budget ->
                    assertEquals(LearnedRetirementStatus.Captured,
                        retirement.capture(session, index, session.learnedRetirementGeneration, params, budget))
                }
                retirement.markSelected()
                retirement.rootPublished()
                spending.run(session, params, null) { budget ->
                    retirement.prepare(session, params, budget)
                    retirement.advance(session, params, budget)
                    retirement.finishSlice(session)
                }
            }

            repeat(2) {
                spending.run(session, params, null) { budget -> retirement.advance(session, params, budget) }
            }

            assertTrue(session.isLearnedClause(0))
            assertFalse(session.isLearnedClause(1))
            assertEquals(true, session.boolValue(1))
        }
    }

    @Test
    fun `an unfundable class grant should leave capture ownership unchanged`() {
        for (native in listOf(false, true)) for (grant in listOf(1L, 10L)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(4, 6)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            val retirement = RootLearnedRetirement()
            val spending = InprocessingSpending(InprocessingKind.Root)
            val params = BacktrackParams(inprocessingRootWork = grant)
            val before = session.work

            spending.run(session, params, null) { budget ->
                assertEquals(LearnedRetirementStatus.Deferred,
                    retirement.capture(session, 0, session.learnedRetirementGeneration, params, budget))
            }

            assertEquals(before, session.work)
            assertFalse(retirement.hasCapture)
            assertFalse(session.hasLearnedRetirementContinuation)
            assertTrue(session.isLearnedClause(0))
        }
    }

    @Test
    fun `an unfunded request should prevent parking a protected cycle`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(1)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 9)
            val retirement = RootLearnedRetirement()
            val spending = InprocessingSpending(InprocessingKind.Root)
            val params = BacktrackParams(inprocessingRootWork = 256L)
            for (index in 0..1) {
                spending.run(session, params, null) { budget ->
                    assertEquals(LearnedRetirementStatus.Captured,
                        retirement.capture(session, index, session.learnedRetirementGeneration, params, budget))
                }
                retirement.markSelected()
                retirement.rootPublished()
                spending.run(session, params, null) { budget ->
                    retirement.prepare(session, params, budget)
                    retirement.advance(session, params, budget)
                    retirement.finishSlice(session)
                }
            }
            val limited = params.copy(inprocessingRootWork = 48L)
            spending.run(session, limited, null) { budget ->
                retirement.advance(session, limited, budget)
                retirement.finishSlice(session)
            }
            assertTrue(session.isLearnedClause(1))

            spending.run(session, params, null) { budget ->
                retirement.advance(session, params, budget)
                retirement.finishSlice(session)
            }

            assertTrue(retirement.hasContinuation(session))
            spending.run(session, params, null) { budget -> retirement.advance(session, params, budget) }
            assertFalse(session.isLearnedClause(1))
        }
    }

    @Test
    fun `capture should respect the containing remainder inside a larger class grant`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(4, 6)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            val retirement = RootLearnedRetirement()
            val spending = InprocessingSpending(InprocessingKind.Root)
            val params = BacktrackParams(inprocessingRootWork = 256L)
            val before = session.work

            spending.run(session, params, null, containingWork = 10L) { budget ->
                assertEquals(LearnedRetirementStatus.Deferred,
                    retirement.capture(session, 0, session.learnedRetirementGeneration, params, budget))
            }

            assertEquals(before, session.work)
            assertFalse(retirement.hasCapture)
            assertTrue(session.isLearnedClause(0))
        }
    }

    @Test
    fun `a cancelled owner should defer recapture until its funded unlink completes`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(4, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(4, 6)))), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            val retirement = RootLearnedRetirement()
            val spending = InprocessingSpending(InprocessingKind.Root)
            val params = BacktrackParams(inprocessingRootWork = 256L)
            spending.run(session, params, null) { budget ->
                assertEquals(LearnedRetirementStatus.Captured,
                    retirement.capture(session, 0, session.learnedRetirementGeneration, params, budget))
            }
            retirement.abandon()
            spending.run(session, params, null) { budget -> retirement.prepare(session, params, budget) }

            spending.run(session, params, null) { budget ->
                assertEquals(LearnedRetirementStatus.Deferred,
                    retirement.capture(session, 0, session.learnedRetirementGeneration, params, budget))
            }

            assertFalse(retirement.hasCapture)
            assertTrue(session.isLearnedClause(0))
            repeat(2) {
                spending.run(session, params, null) { budget -> retirement.advance(session, params, budget) }
            }
            spending.run(session, params, null) { budget ->
                assertEquals(LearnedRetirementStatus.Captured,
                    retirement.capture(session, 0, session.learnedRetirementGeneration, params, budget))
            }
            assertTrue(retirement.hasCapture)
        }
    }
}
