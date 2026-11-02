package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.SharingMemoryStatus
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class NativeMetadataContinuationTest(private val permanentCount: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "permanentCount={0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf<Any>(4_000), arrayOf<Any>(20_000))
    }

    @Test
    fun `growing permanent floors should not delay funded metadata release`() {
        val base = Clause(intArrayOf(8, 10))
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(base, Clause(intArrayOf(1)))), nativeSat = true)
        assertTrue(session.usesNativeSat)
        repeat(permanentCount) { session.addLearnedClause(base, 2, permanent = true) }
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
        val reason = assertNotNull(session.boolReasonClause(1)).toList()
        val generation = session.learnedRetirementGeneration
        repeat(3) { batch ->
            session.addLearnedClause(base, 2, permanent = true)
            repeat(64) { session.addLearnedClause(Clause(intArrayOf(4, 6)), 9) }
            repeat(8) { session.maintainCapWindow(0, 32_768L, 4096L, { false }) { false } }
            assertEquals(64L, session.learnedLifecycle().getValue("learnedMetadataPendingRows"))
            val before = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)
            val stopAt = session.work + 1L

            session.compactLearnedClauses(40L, 256L, { session.work >= stopAt }) { _, _ -> true }
            assertEquals(64L, session.learnedLifecycle().getValue("learnedMetadataPendingRows"))
            repeat(64) { released ->
                val work = session.work
                session.compactLearnedClauses(40L, 256L, { false }) { _, _ -> true }
                assertTrue(session.work - work <= 40L)
                assertEquals((62L - released * 2L).coerceAtLeast(0L),
                    session.learnedLifecycle().getValue("learnedMetadataPendingRows"))
            }
            val after = assertNotNull(session.sharingMemory(maxWork = 1_000_000L).completeValues)

            assertTrue(after.getValue("learnedMetadataCapacityBytes") <

                before.getValue("learnedMetadataCapacityBytes"))
            assertEquals(permanentCount + batch + 2, session.activeLearnedClauseCount)
            assertEquals(generation, session.learnedRetirementGeneration)
            assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
        }
        session.pinBool(4, false)
        session.popLast()
        assertEquals(reason, assertNotNull(session.boolReasonClause(1)).toList())
        session.releaseRetainedState()
        assertEquals(SharingMemoryStatus.STALE, session.sharingMemory().status)
    }
}
