package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeSatStateTest {
    @Test
    fun `indexed retirement cleanup should skip unchanged live postings`() {
        val cleanupWork = listOf(0, 512).map { fillers ->
            val session = session(8, or(6, true, 7, true))
            val configuration = NativeCapConfiguration(false, 2, 6)
            repeat(32) { session.addLearnedClause(Clause(or(0, true, 1, true)), 9) }
            session.maintainCapWindow(31, 65_536L, 1_048_576L, { false },
                canDrop = { false }, nativePolicy = configuration) { false }
            repeat(fillers) { session.addLearnedClause(Clause(or(2, true, 3, true)), 2, permanent = true) }
            session.addLearnedClause(Clause(or(0, true, 1, true)), 10)
            session.maintainCapWindow(32 + fillers, 65_536L, 1_048_576L,
                { false }, nativePolicy = configuration) { false }
            assertEquals(32 + fillers, session.activeLearnedClauseCount)
            val before = session.ownershipWork

            session.reclaimLearnedMemory(Long.MAX_VALUE, 65_536L, 1_048_576L)

            session.ownershipWork - before
        }

        assertTrue(cleanupWork[1] <= cleanupWork[0] + 512L)
    }

    @Test
    fun `new retirement during an older watch sweep should remain eligible for cleanup`() {
        for (slices in listOf(0, 4, 8, 16, 32)) {
            val session = session(8, or(6, true, 7, true))
            val configuration = NativeCapConfiguration(false, 2, 6)
            repeat(32) { session.addLearnedClause(Clause(or(0, true, 1, true)), 9) }
            session.maintainCapWindow(31, 65_536L, 1_048_576L,
                { false }, canDrop = { false }, nativePolicy = configuration) { false }
            session.addLearnedClause(Clause(or(0, true, 1, true)), 10)
            session.maintainCapWindow(32, 65_536L, 1_048_576L,
                { false }, nativePolicy = configuration) { false }
            repeat(slices) { session.reclaimLearnedMemory(Long.MAX_VALUE, 40L, 1_048_576L) }
            session.addLearnedClause(Clause(or(0, true, 1, true)), 11)
            session.maintainCapWindow(32, 65_536L, 1_048_576L,
                { false }, nativePolicy = configuration) { false }

            repeat(128) {
                val before = session.work
                session.reclaimLearnedMemory(Long.MAX_VALUE, 40L, 1_048_576L)
                assertTrue(session.work - before <= 40L)
            }
            val memory = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

            assertEquals(66L, memory.getValue("sessionWatchEntries"), "slices=$slices")
        }
    }

    @Test
    fun `index bootstrap should retain cleanup of earlier generic retirements`() {
        val session = session(8, or(6, true, 7, true))
        val configuration = NativeCapConfiguration(false, 2, 6)
        repeat(32) { session.addLearnedClause(Clause(or(0, true, 1, true)), 9) }
        var attempts = 0
        while (session.activeLearnedClauseCount > 31 && attempts++ < 32) {
            session.maintainCapWindow(31, 4096L, 1_048_576L,
                { false }, nativePolicy = configuration) { false }
        }
        assertEquals(31, session.activeLearnedClauseCount)
        session.addLearnedClause(Clause(or(2, true, 3, true)), 10)
        repeat(4) {
            session.maintainCapWindow(31, 65_536L, 1_048_576L,
                { false }, canDrop = { false }, nativePolicy = configuration) { false }
        }
        session.maintainCapWindow(31, 65_536L, 1_048_576L,
            { false }, nativePolicy = configuration) { false }

        repeat(128) { session.reclaimLearnedMemory(Long.MAX_VALUE, 40L, 1_048_576L) }
        val memory = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertEquals(64L, memory.getValue("sessionWatchEntries"))
    }

    @Test
    fun `custom cap policy should abort a pending native index without retiring its handles`() {
        val session = session(4, or(2, true, 3, true))
        repeat(16) { session.addLearnedClause(Clause(or(0, true, 1, true)), 9) }
        val configuration = NativeCapConfiguration(false, 2, 6)
        session.maintainCapWindow(1, 65_536L, 1_048_576L,
            { session.nativeCapTraversalVisits >= 2L }, nativePolicy = configuration) { false }
        assertEquals(16, session.activeLearnedClauseCount)
        assertTrue(session.nativeCapWindowPending)

        repeat(64) {
            val before = session.work
            session.maintainCapWindow(1, 256L, 64L, { false }, canDrop = { false }) { true }
            assertTrue(session.work - before <= 256L)
        }

        assertEquals(16, session.activeLearnedClauseCount)
        assertFalse(session.nativeCapWindowPending)
        repeat(16) { assertTrue(session.isLearnedClause(it)) }
    }

    @Test
    fun `tiny grants should preserve and resume an initialized native index`() {
        val session = session(4, or(2, true, 3, true))
        repeat(16) { session.addLearnedClause(Clause(or(0, true, 1, true)), 9) }
        val configuration = NativeCapConfiguration(false, 2, 6)
        session.maintainCapWindow(8, 65_536L, 1_048_576L, { false }, nativePolicy = configuration) { false }
        assertEquals(8, session.activeLearnedClauseCount)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 10)

        repeat(64) {
            val before = session.work
            session.maintainCapWindow(4, 64L, 64L, { false }, nativePolicy = configuration) { false }
            assertTrue(session.work - before <= 64L)
        }

        assertEquals(4, session.activeLearnedClauseCount)
        assertFalse(session.nativeCapWindowPending)
        repeat(4) { assertTrue(session.isLearnedClause(it)) }
    }

    @Test
    fun `native remapping should discard private cap markers and queue ownership`() {
        val session = session(4, or(2, true, 3, true))
        repeat(16) { session.addLearnedClause(Clause(or(0, true, 1, true)), 9) }
        val configuration = NativeCapConfiguration(false, 2, 6)
        session.maintainCapWindow(1, 65_536L, 1_048_576L,
            { session.nativeCapTraversalVisits >= 2L }, nativePolicy = configuration) { false }
        val before = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertTrue(session.explanationState.forgetLearnedClauses(10_000_000L, 1_048_576L) { id, _ -> id == 15 })
        val after = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 10)
        repeat(8) {
            session.maintainCapWindow(1, 65_536L, 1_048_576L, { false }, nativePolicy = configuration) { false }
        }

        assertTrue(after.getValue("learnedCapIndexStateBytes") < before.getValue("learnedCapIndexStateBytes"))
        assertEquals(1, session.activeLearnedClauseCount)
        assertTrue(session.isLearnedClause(0))
        assertFalse(session.isLearnedClause(1))
    }

    private fun session(numVars: Int, vararg clauses: IntArray): PropagationSession = PropagationSession(
        Problem(
            numBoolVars = numVars,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = clauses.map<IntArray, Factor> { Clause(it) }.toTypedArray(),
        ),
        nativeSat = true,
    )

    private fun or(a: Int, aPos: Boolean, b: Int, bPos: Boolean) = intArrayOf(Lit.make(a, aPos), Lit.make(b, bPos))

    @Test
    fun `a borrowed clause array should remain independent of its native payload`() {
        val session = session(4, or(2, true, 3, true))
        val clause = Clause(or(0, true, 1, true))
        session.addLearnedClause(clause, 9)

        clause.literals[0] = Lit.make(2, true)
        clause.literals[1] = Lit.make(3, true)
        session.pinBool(0, false)

        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `native payload growth should preserve the last learned implication`() {
        for (count in listOf(1, 2, 3, 63, 64, 65, 66)) {
            val session = session(8, or(6, true, 7, true))
            repeat(count - 1) { session.addLearnedClause(Clause(or(2, true, 3, true)), 9) }
            session.addLearnedClause(Clause(or(0, true, 1, true)), 9)

            session.pinBool(0, false)

            assertEquals(true, session.boolValue(1), "count=$count")
        }
    }

    @Test
    fun `empty native payload pages should release reference capacity without remapping`() {
        val session = session(4, or(2, true, 3, true))
        repeat(129) { index ->
            session.addLearnedClause(Clause(or(0, true, 1, true)), 9, permanent = index == 64 || index == 128)
        }
        val before = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        repeat(64) {
            val start = session.work
            session.maintainCapWindow(2, 256L, 64L, { false }) { false }
            assertTrue(session.work - start <= 256L)
        }
        val after = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertEquals(129, session.learnedClauseCount)
        assertEquals(2, session.activeLearnedClauseCount)
        assertTrue(after.getValue("learnedPayloadReferenceCapacity") <
            before.getValue("learnedPayloadReferenceCapacity"))
    }

    @Test
    fun `native compaction should preserve a survivor across released payload pages`() {
        val session = session(4, or(2, true, 3, true))
        repeat(65) { index -> session.addLearnedClause(Clause(or(0, true, 1, true)), 9, permanent = index == 64) }
        repeat(32) { session.maintainCapWindow(1, 256L, 64L, { false }) { false } }
        assertTrue(session.explanationState.forgetLearnedClauses(1_000_000L, 1_048_576L) { _, _ -> true })

        session.pinBool(0, false)

        assertEquals(true, session.boolValue(1))
        assertEquals(1, session.learnedClauseCount)
    }

    @Test
    fun `native retirement should release a large payload within small grants`() {
        val session = session(2050, or(2048, true, 2049, true))
        session.addLearnedClause(Clause(IntArray(2048) { Lit.make(it, true) }), 9)
        val before = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        repeat(2) {
            val start = session.work
            session.maintainCapWindow(0, 40L, 64L, { false }) { false }
            assertTrue(session.work - start <= 40L)
        }
        val after = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

        assertEquals(8192L, before.getValue("learnedPayloadBytes"))
        assertEquals(0L, after.getValue("learnedPayloadBytes"))
        assertEquals(0L, after.getValue("learnedArenaCapacityBytes"))
        assertEquals(8192L + before.getValue("learnedMetadataCapacityBytes") -
            after.getValue("learnedMetadataCapacityBytes"),
            before.getValue("ownedPrimitiveBytes") - after.getValue("ownedPrimitiveBytes"))
        assertEquals(0L, after.getValue("learnedPayloadReferenceCapacity"))
        assertEquals(before.getValue("sessionWatchCapacityBytes"), after.getValue("sessionWatchCapacityBytes"))
        assertEquals(1, session.learnedClauseCount)
        assertEquals(0, session.activeLearnedClauseCount)
    }

    @Test
    fun `an appended native payload should remain independent of retired slots`() {
        val session = session(6, or(4, true, 5, true))
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9)
        repeat(2) { session.maintainCapWindow(0, 40L, 64L, { false }) { false } }
        session.addLearnedClause(Clause(or(2, true, 3, true)), 9)

        session.pinBool(0, false)
        session.pinBool(2, false)

        assertNull(session.boolValue(1))
        assertEquals(true, session.boolValue(3))
        assertEquals(2, session.learnedClauseCount)
        assertEquals(1, session.activeLearnedClauseCount)
    }

    @Test
    fun `retired native clauses should stop propagating without renumbering survivors`() {
        val session = session(4, or(2, true, 3, true))
        session.addLearnedClause(Clause(or(2, true, 3, true)), 9, permanent = true)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9)

        repeat(2) { session.maintainCapWindow(1, 40L, 64L, { false }) { false } }
        session.pinBool(0, false)

        assertNull(session.boolValue(1))
        assertEquals(2, session.learnedClauseCount)
        assertEquals(1, session.activeLearnedClauseCount)
    }

    @Test
    fun `retiring a watched clause should preserve the captured survivor phase`() {
        val session = session(4, or(0, true, 2, true))
        session.addLearnedClause(Clause(or(0, true, 3, true)), 9)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9, permanent = true)
        session.addLearnedClause(Clause(or(0, true, 1, false)), 9, permanent = true)
        repeat(8) { session.maintainCapWindow(2, 64L, 64L, { false }) { false } }
        assertEquals(2, session.activeLearnedClauseCount)
        var phase: Boolean? = null
        session.retractingListener = { if (it == 1) phase = session.boolValue(1) }

        session.pinBool(0, false)

        assertEquals(true, phase)
    }

    @Test
    fun `cleaning retired watches should preserve the captured survivor phase`() {
        val session = session(4, or(0, true, 2, true))
        session.addLearnedClause(Clause(or(0, true, 3, true)), 9)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9, permanent = true)
        session.addLearnedClause(Clause(or(0, true, 1, false)), 9, permanent = true)
        repeat(8) { session.maintainCapWindow(2, 64L, 64L, { false }) { false } }
        assertEquals(2, session.activeLearnedClauseCount)
        session.reclaimLearnedMemory(Long.MAX_VALUE, 65_536L, 1_048_576L)
        var phase: Boolean? = null
        session.retractingListener = { if (it == 1) phase = session.boolValue(1) }

        session.pinBool(0, false)

        assertEquals(true, phase)
    }

    @Test
    fun `funded watch cleanup should preserve survivor phase across posting sizes`() {
        for ((fillers, grant) in listOf(14 to 40L, 256 to 65_536L, 2560 to 65_536L)) {
            val session = session(4, or(0, true, 2, true))
            session.addLearnedClause(Clause(or(0, true, 3, true)), 9)
            session.addLearnedClause(Clause(or(0, true, 1, true)), 9, permanent = true)
            repeat(fillers) { session.addLearnedClause(Clause(or(0, true, 2, true)), 9, permanent = true) }
            session.addLearnedClause(Clause(or(0, true, 1, false)), 9, permanent = true)
            repeat(8) { session.maintainCapWindow(fillers + 2, 65_536L, 1_048_576L, { false }) { false } }
            assertEquals(fillers + 2, session.activeLearnedClauseCount)
            repeat(if (grant == 40L) 64 else 16) {
                val start = session.work
                session.reclaimLearnedMemory(Long.MAX_VALUE, grant, 1_048_576L)
                assertTrue(session.work - start <= grant)
            }
            var phase: Boolean? = null
            session.retractingListener = { if (it == 1) phase = session.boolValue(1) }

            session.pinBool(0, false)

            assertEquals(true, phase, "fillers=$fillers grant=$grant")
        }
    }

    @Test
    fun `propagation between cleanup slices should preserve live watch postings`() {
        for (slices in listOf(0, 8, 16, 32, 64)) {
            val session = session(5, intArrayOf(Lit.make(0, true), Lit.make(2, true), Lit.make(3, true)))
            session.addLearnedClause(Clause(or(0, true, 1, true)), 9, permanent = true)
            repeat(64) { session.addLearnedClause(Clause(or(0, true, 4, true)), 9) }
            repeat(8) { session.maintainCapWindow(1, 65_536L, 1_048_576L, { false }) { false } }
            assertEquals(1, session.activeLearnedClauseCount)
            repeat(slices) { session.reclaimLearnedMemory(Long.MAX_VALUE, 40L, 1_048_576L) }

            session.pinBool(0, false)
            repeat(256) {
                val start = session.work
                session.reclaimLearnedMemory(Long.MAX_VALUE, 40L, 1_048_576L)
                assertTrue(session.work - start <= 40L)
            }
            val memory = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

            assertEquals(true, session.boolValue(1), "slices=$slices")
            assertEquals(4L, memory.getValue("sessionWatchEntries"), "slices=$slices")
        }
    }

    @Test
    fun `retiring a long watched tail should preserve the captured survivor phase`() {
        for (fillers in listOf(256, 2560)) {
            val session = session(4, or(0, true, 2, true))
            session.addLearnedClause(Clause(or(0, true, 3, true)), 9)
            session.addLearnedClause(Clause(or(0, true, 1, true)), 9, permanent = true)
            repeat(fillers) { session.addLearnedClause(Clause(or(0, true, 2, true)), 9, permanent = true) }
            session.addLearnedClause(Clause(or(0, true, 1, false)), 9, permanent = true)
            repeat(8) { session.maintainCapWindow(fillers + 2, 65_536L, 1_048_576L, { false }) { false } }
            assertEquals(fillers + 2, session.activeLearnedClauseCount)
            var phase: Boolean? = null
            session.retractingListener = { if (it == 1) phase = session.boolValue(1) }

            session.pinBool(0, false)

            assertEquals(true, phase)
        }
    }

    @Test
    fun `moving an active watch past retired tail entries should preserve the survivor phase`() {
        for (retired in listOf(1, 16, 256)) {
            val session = session(4, intArrayOf(Lit.make(0, true), Lit.make(2, true), Lit.make(3, true)))
            session.addLearnedClause(Clause(or(0, true, 1, true)), 9, permanent = true)
            session.addLearnedClause(Clause(or(0, true, 1, false)), 9, permanent = true)
            repeat(retired) { session.addLearnedClause(Clause(or(0, true, 2, true)), 9) }
            repeat(8) { session.maintainCapWindow(2, 65_536L, 1_048_576L, { false }) { false } }
            assertEquals(2, session.activeLearnedClauseCount)
            var phase: Boolean? = null
            session.retractingListener = { if (it == 1) phase = session.boolValue(1) }

            session.pinBool(0, false)

            assertEquals(false, phase, "retired=$retired")
        }
    }

    @Test
    fun `retiring a watched tail beyond four thousand entries should preserve the survivor phase`() {
        val session = session(4, or(0, true, 2, true))
        session.addLearnedClause(Clause(or(0, true, 3, true)), 9)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9, permanent = true)
        repeat(4096) { session.addLearnedClause(Clause(or(0, true, 2, true)), 9, permanent = true) }
        session.addLearnedClause(Clause(or(0, true, 1, false)), 9, permanent = true)
        repeat(64) {
            val start = session.work
            session.maintainCapWindow(4098, 65_536L, 1_048_576L, { false }) { false }
            assertTrue(session.work - start <= 65_536L)
        }
        assertEquals(4098, session.activeLearnedClauseCount)
        var phase: Boolean? = null
        session.retractingListener = { if (it == 1) phase = session.boolValue(1) }

        session.pinBool(0, false)

        assertEquals(true, phase)
    }

    @Test
    fun `a retired watch prefix should propagate within linear charged work`() {
        for (count in listOf(128, 256, 512)) {
            val session = session(5, or(3, true, 4, true))
            repeat(count) { session.addLearnedClause(Clause(or(0, true, 1, true)), 9) }
            repeat(count) { session.addLearnedClause(Clause(or(0, true, 2, true)), 9, permanent = true) }
            repeat(32) { session.maintainCapWindow(count, 65_536L, 1_048_576L, { false }) { false } }
            assertEquals(count, session.activeLearnedClauseCount)
            val start = session.work

            session.pinBool(0, false)

            assertNull(session.boolValue(1))
            assertEquals(true, session.boolValue(2))
            assertTrue(session.work - start <= 64L * (2L * count + 1L), "count=$count")
        }
    }

    @Test
    fun `successive active watch moves through retired holes should preserve the survivor phase`() {
        val session = session(6, or(4, true, 5, true))
        session.addLearnedClause(Clause(or(0, true, 4, true)), 9)
        session.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true), Lit.make(3, true))),
            9, permanent = true)
        session.addLearnedClause(Clause(or(0, true, 5, true)), 9)
        session.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(3, true), Lit.make(4, true))),
            9, permanent = true)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9, permanent = true)
        session.addLearnedClause(Clause(or(0, true, 1, false)), 9, permanent = true)
        repeat(8) { session.maintainCapWindow(4, 65_536L, 1_048_576L, { false }) { false } }
        assertEquals(4, session.activeLearnedClauseCount)
        var phase: Boolean? = null
        session.retractingListener = { if (it == 1) phase = session.boolValue(1) }

        session.pinBool(0, false)

        assertEquals(false, phase)
    }

    @Test
    fun `small cleanup grants should release watches when an ordered copy cannot fit`() {
        for (grant in listOf(40L, 42L, 48L)) {
            val session = session(3, or(0, true, 2, true))
            session.addLearnedClause(Clause(or(0, true, 1, true)), 9)
            repeat(15) { session.addLearnedClause(Clause(or(0, true, 1, true)), 9, permanent = true) }
            repeat(8) { session.maintainCapWindow(15, 64L, 64L, { false }) { false } }
            val before = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

            repeat(64) {
                val start = session.work
                session.reclaimLearnedMemory(Long.MAX_VALUE, grant, 1_048_576L)
                assertTrue(session.work - start <= grant)
            }
            val after = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

            assertTrue(after.getValue("sessionWatchCapacityBytes") < before.getValue("sessionWatchCapacityBytes"))
        }
    }

    @Test
    fun `native cap retirement should protect a live root reason`() {
        val session = session(4, or(2, true, 3, true))
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9)
        session.addLearnedClause(Clause(intArrayOf(Lit.make(0, false))), 1, permanent = true)

        val retired = session.maintainCapWindow(1, 40L, 64L, { false }) { false }

        assertEquals(0, retired)
        assertTrue(session.isLearnedClause(0))
        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `native cap windows should resume bounded progress while clauses are appended`() {
        val session = session(4, or(2, true, 3, true))
        session.addLearnedClause(Clause(or(2, true, 3, true)), 9, permanent = true)
        repeat(3) { session.addLearnedClause(Clause(or(0, true, 1, true)), 9) }
        var retired = 0

        repeat(16) { attempt ->
            val before = session.work
            retired += session.maintainCapWindow(1, 40L, 64L, { false }) { false }
            assertTrue(session.work - before <= 40L)
            if (attempt == 0) session.addLearnedClause(Clause(or(0, true, 1, true)), 9)
        }

        assertEquals(4, retired)
        assertEquals(1, session.activeLearnedClauseCount)
    }

    @Test
    fun `native cap cancellation at the debit should preserve clause policy`() {
        val session = session(4, or(2, true, 3, true))
        session.addLearnedClause(Clause(or(2, true, 3, true)), 9, permanent = true)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9)
        val before = session.work

        val retired = session.maintainCapWindow(1, 40L, 64L, { session.work - before >= 40L }) {
            error("cancelled policy callback")
        }

        assertEquals(0, retired)
        assertTrue(session.isLearnedClause(1))
        assertEquals(40L, session.work - before)
    }

    @Test
    fun `unfunded native windows should preserve learned clauses`() {
        for ((work, scratch) in listOf(0L to 64L, 39L to 64L, 40L to 0L)) {
            val session = session(4, or(2, true, 3, true))
            session.addLearnedClause(Clause(or(2, true, 3, true)), 9, permanent = true)
            session.addLearnedClause(Clause(or(0, true, 1, true)), 9)

            session.maintainCapWindow(1, work, scratch, { false }) { false }

            assertTrue(session.isLearnedClause(1))
            assertEquals(2, session.activeLearnedClauseCount)
        }
    }

    @Test
    fun `native windows should defer pending first reassertions`() {
        val session = session(4, or(2, true, 3, true))
        session.addLearnedClause(Clause(or(2, true, 3, true)), 9, permanent = true)
        session.pinBool(0, false)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9)
        session.popToLevel(0)

        val retired = session.maintainCapWindow(1, 40L, 64L, { false }) { false }

        assertEquals(0, retired)
        assertTrue(session.isLearnedClause(1))
    }

    @Test
    fun `retired clauses should stay absent from glue export`() {
        val session = session(4, or(2, true, 3, true))
        session.addLearnedClause(Clause(or(2, true, 3, true)), 9, permanent = true)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9)
        repeat(2) { session.maintainCapWindow(1, 40L, 64L, { false }) { false } }

        val exported = session.exportGlueClauses(9, 2, skipPermanent = true)

        assertTrue(exported.isEmpty())
    }

    @Test
    fun `retired clauses should stay absent from binary implications`() {
        val session = session(4, or(2, true, 3, true))
        session.addLearnedClause(Clause(or(2, true, 3, true)), 9, permanent = true)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9)
        repeat(2) { session.maintainCapWindow(1, 40L, 64L, { false }) { false } }
        val partners = ArrayList<Int>()

        session.explanationState.forEachBinaryPartner(Lit.make(0, true)) { partners.add(it) }

        assertTrue(partners.isEmpty())
    }

    @Test
    fun `compacting retired slots should preserve exact retirement counts`() {
        val session = session(4, or(2, true, 3, true))
        session.addLearnedClause(Clause(or(2, true, 3, true)), 9, permanent = true)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9)
        repeat(2) { session.maintainCapWindow(1, 40L, 64L, { false }) { false } }
        session.forgetLearnedClauses { _, _ -> true }
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9)

        repeat(2) { session.maintainCapWindow(1, 40L, 64L, { false }) { false } }

        assertEquals(2L, session.learnedLifecycle()["learningClauseRetired"])
        assertEquals(1, session.activeLearnedClauseCount)
    }

    @Test
    fun `releasing native cap state should preserve its charged totals`() {
        val session = session(4, or(2, true, 3, true))
        session.addLearnedClause(Clause(or(2, true, 3, true)), 9, permanent = true)
        session.addLearnedClause(Clause(or(0, true, 1, true)), 9)
        session.maintainCapWindow(1, 40L, 64L, { false }) { false }
        val before = session.learnedLifecycle()

        session.releaseRetainedState()

        assertEquals(before, session.learnedLifecycle())
        assertEquals(40L, session.learnedLifecycle()["learnedCapWork"])
        assertEquals(0, session.maintainCapWindow(1, 40L, 64L, { false }) { false })
    }

    @Test
    fun `a root learned reason should remain in the conflict core after compaction`() {
        val session = session(4, or(2, true, 3, true))
        session.addLearnedClause(Clause(or(0, true, 1, true)), 2)
        session.addLearnedClause(Clause(intArrayOf(Lit.make(0, true))), 1, permanent = true)
        session.forgetLearnedClauses { index, _ -> index != 0 }

        val conflict = assertIs<PropagationResult.Unsat>(session.pinBool(0, false))

        assertEquals(setOf(1), conflict.conflictFactors.toSet())
    }

    @Test
    fun `surviving clauses should propagate after repeated reductions and additions`() {
        val session = session(8, or(6, true, 7, true))
        session.addLearnedClause(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true))), 3)
        session.addLearnedClause(Clause(or(0, true, 5, true)), 2)
        session.addLearnedClause(Clause(intArrayOf(Lit.make(3, true), Lit.make(4, true), Lit.make(5, true))), 3)
        session.forgetLearnedClauses { index, _ -> index != 1 }
        session.addLearnedClause(Clause(or(0, true, 3, true)), 2)
        session.forgetLearnedClauses { index, _ -> index != 0 }
        session.pinBool(3, false)

        session.pinBool(4, false)

        assertEquals(true, session.boolValue(0))
        assertEquals(true, session.boolValue(5))
    }

    @Test
    fun `learned watch scans should find an open prefix after backtracking`() {
        val session = session(7, or(5, true, 6, true))
        session.addLearnedClause(Clause(IntArray(5) { Lit.make(it, true) }), 3)
        session.pinBool(0, false)
        session.pinBool(1, false)
        session.popToLevel(0)
        session.pinBool(4, false)
        session.pinBool(2, false)
        session.pinBool(3, false)

        session.pinBool(0, false)

        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `opposite pins after undo should propagate opposite deductions`() {
        val session = session(2, or(0, false, 1, true), or(0, true, 1, false))
        session.pinBool(0, true)
        assertEquals(true, session.boolValue(1))
        session.popToLevel(0)

        session.pinBool(0, false)

        assertEquals(false, session.boolValue(1))
        session.popToLevel(0)
        session.pinBool(0, true)
        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `relocated watches should propagate after backtracking`() {
        val session = session(3, intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)))

        session.pinBool(0, false)
        session.pinBool(1, false)
        assertEquals(true, session.boolValue(2))
        session.popToLevel(0)
        session.pinBool(2, false)
        session.pinBool(1, false)

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `a learned unit should propagate when its deepest premise is reassigned`() {
        val session = session(4, or(0, true, 1, true))
        session.pinBool(0, true)
        session.pinBool(1, true)
        session.pinBool(2, true)
        session.addLearnedClause(Clause(intArrayOf(Lit.make(0, false), Lit.make(2, false), Lit.make(3, true))), 2)
        session.popToLevel(1)

        session.pinBool(2, true)

        assertEquals(true, session.boolValue(3))
    }

    @Test
    fun `a conflicting learned clause should propagate after deeper premises retract`() {
        val session = session(4, or(0, true, 1, true))
        session.pinBool(0, true)
        session.pinBool(1, true)
        session.pinBool(2, true)
        session.pinBool(3, false)
        assertIs<PropagationResult.Unsat>(
            session.addLearnedClause(
                Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, false), Lit.make(3, true))),
                3,
            ),
        )
        session.popToLevel(2)

        session.pinBool(2, true)

        assertEquals(true, session.boolValue(3))
    }

    @Test
    fun `forget should renumber survivors and preserve their propagation`() {
        val session = session(4, intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)))
        session.addLearnedClause(Clause(or(0, true, 1, true)), lbd = 2) // index 0 — kept
        session.addLearnedClause(Clause(or(2, true, 3, true)), lbd = 2) // index 1 — dropped
        session.addLearnedClause(Clause(or(0, true, 2, true)), lbd = 2) // index 2 — kept
        session.addLearnedClause(Clause(or(1, true, 3, true)), lbd = 2) // index 3 — dropped
        assertEquals(4, session.learnedClauseCount)

        session.forgetLearnedClauses { idx, _ -> idx % 2 == 0 }
        assertEquals(2, session.learnedClauseCount)

        session.pinBool(0, false)
        assertTrue(session.boolValue(1) == true)
        assertTrue(session.boolValue(2) == true)
    }

    @Test
    fun `a dropped clause should not propagate`() {
        val session = session(4, intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)))
        session.addLearnedClause(Clause(or(0, true, 1, true)), lbd = 2) // index 0 — kept
        session.addLearnedClause(Clause(or(2, true, 3, true)), lbd = 2) // index 1 — dropped

        session.forgetLearnedClauses { idx, _ -> idx == 0 }
        assertEquals(1, session.learnedClauseCount)

        session.pinBool(0, true)
        session.pinBool(2, false)
        assertNull(session.boolValue(3))
    }

    @Test
    fun `glue export should read native learned clause literals`() {
        val session = session(3, intArrayOf(Lit.make(0, true), Lit.make(1, true)))
        session.addLearnedClause(Clause(or(0, false, 1, true)), lbd = 2)
        session.addLearnedClause(Clause(or(1, false, 2, true)), lbd = 8) // above the glue LBD cap
        val glue = session.exportGlueClauses(maxLbd = 2, maxLen = 8)
        assertEquals(1, glue.size)
    }
}
