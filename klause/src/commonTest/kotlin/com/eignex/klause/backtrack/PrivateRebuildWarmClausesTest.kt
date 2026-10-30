package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.RebuildStep
import com.eignex.klause.presolve.SourceRebuilds
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class PrivateRebuildWarmClausesTest {
    @Test
    fun `only owned clauses over retained columns should enter the private generation`() {
        for (native in listOf(false, true)) {
            val source = Problem(4, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 2)), Clause(intArrayOf(4, 6)),
            )).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 2)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 2, origin = 1)

            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, SourceRebuilds(listOf(
                RebuildStep.SatisfyClauses(0, listOf(intArrayOf(0, 2), intArrayOf(1, 2))),
            )), arrayOfNulls(4), 1_048_576L, 65_536L, Cancellation.Never))

            assertEquals(1, warm.clauses.size)
            val copied = warm.clauses.single() as Clause
            assertContentEquals(intArrayOf(4, 6), copied.literals)
            assertNotSame(session.learnedClauseLiterals(0), copied.literals)
            assertEquals(3, session.learnedClauseCount)
        }
    }

    @Test
    fun `globally fixed false literals should be removed from retained learned clauses`() {
        for (native in listOf(false, true)) {
            val source = Problem(3, 0, emptyArray(), arrayOf(
                Clause(intArrayOf(0)), Clause(intArrayOf(2, 4)),
            )).bake()
            val session = PropagationSession(source, nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(1, 2, 4)), 2)

            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, SourceRebuilds(emptyList()),
                Array(3) { session.boolValue(it) }, 1_048_576L, 65_536L, Cancellation.Never))

            assertContentEquals(intArrayOf(2, 4), (warm.clauses.single() as Clause).literals)
            assertEquals(2L, warm.literals)
            assertEquals(1536L, warm.modeledBytes)
        }
    }

    @Test
    fun `insufficient transfer grants should preserve the generation without copied learning`() {
        for ((bytes, work) in listOf(0L to 65_536L, 1_048_576L to 0L)) {
            val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()
            val session = PropagationSession(source)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)

            val warm = assertNotNull(PrivateRebuildWarmClauses.capture(session, SourceRebuilds(emptyList()),
                arrayOfNulls(2), bytes, work, Cancellation.Never))

            assertEquals(0, warm.clauses.size)
            assertEquals(0L, warm.modeledBytes)
        }
    }

    @Test
    fun `containing cancellation should withhold a learned snapshot`() {
        val source = Problem(2, 0, emptyArray(), arrayOf(Clause(intArrayOf(0, 2)))).bake()

        val warm = PrivateRebuildWarmClauses.capture(PropagationSession(source), SourceRebuilds(emptyList()),
            arrayOfNulls(2), 1_048_576L, 65_536L, Cancellation { true })

        assertNull(warm)
    }
}
