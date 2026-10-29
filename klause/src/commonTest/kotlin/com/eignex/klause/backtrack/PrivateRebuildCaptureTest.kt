package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.OWN_ORIGIN
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrivateRebuildCaptureTest {
    private fun source(): Problem = Problem(5, 0, emptyArray(), arrayOf<Factor>(
        Clause(intArrayOf(0, 2)), Clause(intArrayOf(1, 4)),
        Clause(intArrayOf(0, 6)), Clause(intArrayOf(0, 6, 2)),
        Clause(intArrayOf(1, 6)), Clause(intArrayOf(1, 6, 4)),
        Clause(intArrayOf(6, 8)), Clause(intArrayOf(6, 9)),
    ))

    private fun capture(session: PropagationSession, limit: Int = 1024, work: Long = 4096L): Map<String, Long> {
        val stats = SearchStatsSink()
        val params = BacktrackParams(inprocessingRootWork = work)
        val budget = InprocessingBudget(session, params, stats, InprocessingKind.Root, work)
        PrivateRebuildCapture.observe(session, budget, limit)
        return stats.snapshot().learning
    }

    @Test
    fun `global root progress should distinguish new elimination from stable simplification`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(source().bake(), nativeSat = native)
            val before = capture(session)
            session.addRootDerivedClause(Clause(intArrayOf(6)), 1, OWN_ORIGIN, false)

            val after = capture(session)

            assertEquals(0L, before["inprocessRootRebuildNewCandidates"])
            assertEquals(1L, after["inprocessRootRebuildNewCandidates"])
            assertEquals(1L, after["inprocessRootRebuildProgressPins"])
            assertEquals(6L, after["inprocessRootRebuildStableRemoved"])
            assertNull(session.boolValue(0))
            assertEquals(5, session.problem.numBoolVars)
        }
    }

    @Test
    fun `initial root deductions should not count as search progress`() {
        val original = source()
        val pinned = Problem(5, 0, emptyArray(), original.factors + Clause(intArrayOf(6)))
        val session = PropagationSession(pinned.bake())

        val result = capture(session)

        assertEquals(0L, result["inprocessRootRebuildProgressPins"])
        assertEquals(0L, result["inprocessRootRebuildNewCandidates"])
    }

    @Test
    fun `partial scans should withhold candidate conclusions and respect the allowance`() {
        for ((limit, work) in listOf(51 to 4096L, 1024 to 51L, 1024 to 1L)) {
            val session = PropagationSession(source().bake())
            val start = session.work

            val result = capture(session, limit, work)

            assertEquals(1L, result["inprocessRootRebuildPartial"])
            assertNull(result["inprocessRootRebuildComplete"])
            assertNull(result["inprocessRootRebuildNewCandidates"])
            assertTrue(session.work - start <= minOf(limit.toLong(), work))
        }
    }

    @Test
    fun `assumptions and conditional cutoffs should withhold capture`() {
        for (scoped in listOf(false, true)) {
            val session = PropagationSession(source().bake())
            if (scoped) session.addLearnedClause(Clause(intArrayOf(6)), 1, permanent = true)
            else session.seed(Assumptions(bools = mapOf(3 to true)))

            val result = capture(session)

            assertNull(result["inprocessRootRebuildAttempts"])
        }
    }

    @Test
    fun `cancellation should withhold a complete view`() {
        val session = PropagationSession(source().bake())
        var polls = 0
        val result = session.withCancellation(Cancellation { ++polls >= 4 }) { capture(session) }

        assertNull(result["inprocessRootRebuildComplete"])
        assertNull(result["inprocessRootRebuildNewCandidates"])
    }
}
