package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.SharingMemoryStatus
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class LearnedMemoryProductionTest(private val cap: Int, private val native: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "cap={0}, native={1}")
        fun parameters(): List<Array<Any>> = listOf(4000, 20_000).flatMap { cap ->
            listOf(false, true).map { native -> arrayOf<Any>(cap, native) }
        }
    }

    @Test
    fun `production cap grants should release growing tails beside protected floors`() {
        val floor = Clause(intArrayOf(8, 10))
        val problem = Problem(6, 0, emptyArray(), arrayOf(floor, Clause(intArrayOf(1))))
        val session = PropagationSession(problem, nativeSat = native)
        assertEquals(native, session.usesNativeSat)
        repeat(cap - 1) { session.addLearnedClause(floor, 2, permanent = true) }
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        val reason = assertNotNull(session.boolReasonClause(1)).toList()
        val generation = session.learnedRetirementGeneration
        val rows = session.explanationState.learned.initializedStore
        val bytes = rows?.primitiveBytes
        val references = rows?.referenceCapacity
        var nativeBytes: Long? = null
        var nativeReferences: Long? = null
        val params = BacktrackPresets.satOptimized(maxLearnedClauses = cap, inprocess = false)
        repeat(3) {
            repeat(64) { session.addLearnedClause(Clause(intArrayOf(4, 6)), 9) }
            assertEquals(cap + 64, session.activeLearnedClauseCount)
            repeat(16) {
                val before = session.work
                forgetIfOverCap(session, params)
                assertTrue(session.work - before <= params.learnedMemoryWork)
            }
            assertEquals(cap, session.activeLearnedClauseCount)
            if (rows != null) {
                assertEquals(bytes, rows.primitiveBytes)
                assertEquals(references, rows.referenceCapacity)
            }
            if (native) {
                val observed = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
                val owned = observed.getValue("ownedPrimitiveBytes")
                val slots = observed.getValue("ownedReferenceSlots")
                nativeBytes?.let { assertEquals(it, owned) }
                nativeReferences?.let { assertEquals(it, slots) }
                nativeBytes = owned
                nativeReferences = slots
            }
            assertEquals(generation, session.learnedRetirementGeneration)
            assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
        }
        session.pinBool(4, false)
        assertEquals(true, session.boolValue(5))
        session.popLast()
        assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
        session.releaseRetainedState()
        assertEquals(SharingMemoryStatus.STALE, session.sharingMemory().status)
        val replacement = PropagationSession(problem, nativeSat = native)
        assertEquals(0, replacement.learnedClauseCount)
        replacement.addLearnedClause(Clause(intArrayOf(4, 6)), 9)
        replacement.pinBool(2, false)
        assertEquals(true, replacement.boolValue(3))
    }
}
