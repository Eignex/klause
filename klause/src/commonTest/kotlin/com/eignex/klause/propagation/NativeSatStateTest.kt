package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeSatStateTest {
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
