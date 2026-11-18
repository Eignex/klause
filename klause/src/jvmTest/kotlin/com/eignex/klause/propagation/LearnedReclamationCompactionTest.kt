package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class LearnedReclamationCompactionTest(
    private val maxWork: Long,
    private val maxScratchBytes: Long,
    private val funded: Boolean,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "work={0}, scratch={1}, funded={2}")
        fun parameters(): List<Array<Any>> = listOf(
            arrayOf(10_000_000L, 1_048_576L, false),
            arrayOf(10_000_000L, 4_194_304L, false),
            arrayOf(20_000_000L, 1_048_576L, true),
            arrayOf(20_000_000L, 4_194_304L, true),
            arrayOf(20_000_000L, 262_144L, false),
        )

        @JvmStatic
        @BeforeClass
        fun initializePropagation() {
            val session = PropagationSession(Problem(2, 0, emptyArray(),
                arrayOf(Clause(intArrayOf(0, 2)))), nativeSat = true)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            session.compactLearnedClauses(20_000_000L, 1_048_576L, { false }) { _, _ -> false }
        }
    }

    @Test
    fun `native compaction should preserve unfunded stores with bounded retry work`() {
        val session = PropagationSession(Problem(6, 0, emptyArray(),
            arrayOf(Clause(intArrayOf(8, 10)))), nativeSat = true)
        assertTrue(session.usesNativeSat)
        repeat(4000) { session.addLearnedClause(Clause(intArrayOf(0, 2)), 9) }
        val revision = session.learnedClauseRevision
        if (funded) {
            assertEquals(false, session.compactLearnedClauses(10_000_000L, maxScratchBytes,
                { false }) { _, _ -> false })
        }

        repeat(if (funded) 1 else 3) {
            val before = session.work
            val published = session.compactLearnedClauses(maxWork, maxScratchBytes, { false }) { _, _ -> false }
            val work = session.work - before

            assertEquals(funded, published, "work=$work")
            if (funded) {
                assertEquals(0, session.learnedClauseCount)
                assertTrue(work <= maxWork)
            } else {
                assertTrue(work < 256L, "unfunded preparation charged $work work")
                assertEquals(4000, session.learnedClauseCount)
                assertEquals(revision, session.learnedClauseRevision)
            }
        }
        if (!funded) {
            session.pinBool(0, false)
            assertEquals(true, session.boolValue(1))
        }
    }
}
