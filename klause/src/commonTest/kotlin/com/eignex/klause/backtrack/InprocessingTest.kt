package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.ReclamationStatus
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InprocessingTest {

    private class CountingPass(
        override val preservesVariables: Boolean = true,
        private val work: Long = 0L,
        private val grow: Boolean = false,
    ) : InprocessingPass {
        var runs = 0
        var resets = 0

        override fun run(session: PropagationSession, params: BacktrackParams, stats: SearchStatsSink?) {
            runs++
            session.chargeInprocessingWork(work)
            if (grow) session.addRootDerivedClause(Clause(intArrayOf(0, 2)), 2, 0, false)
        }

        override fun reset() {
            resets++
        }
    }

    private fun problem(): Problem = Problem(
        numBoolVars = 2,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true)))),
    )

    private fun session(): PropagationSession = PropagationSession(problem().bake())

    @Test
    fun `a variable-eliminating pass should be rejected at construction`() {
        assertFailsWith<IllegalArgumentException> {
            Inprocessing(listOf(CountingPass(preservesVariables = false)), cadence = 1)
        }
    }

    @Test
    fun `passes should run only every cadence-th restart`() {
        val pass = CountingPass()
        val loop = Inprocessing(listOf(pass), cadence = 3)
        val session = session()
        val params = BacktrackParams()
        repeat(7) { loop.onRestart(session, params) }
        assertEquals(2, pass.runs)
    }

    @Test
    fun `reset should restore the cadence countdown and reset every pass`() {
        val pass = CountingPass()
        val loop = Inprocessing(listOf(pass), cadence = 2)
        val session = session()
        val params = BacktrackParams()
        loop.onRestart(session, params)
        loop.reset()
        loop.onRestart(session, params)
        assertEquals(0, pass.runs)
        assertEquals(1, pass.resets)
        loop.onRestart(session, params)
        assertEquals(1, pass.runs)
    }

    @Test
    fun `from should build a loop only for an unseeded search with a pass enabled`() {
        assertNull(Inprocessing.from(BacktrackParams()))
        assertNotNull(Inprocessing.from(BacktrackParams(vivification = true)))
        assertNull(
            Inprocessing.from(
                BacktrackParams(vivification = true, assumptions = Assumptions(bools = mapOf(0 to true))),
            ),
        )
    }

    @Test
    fun `work triggers should run at safe roots without a restart`() {
        val pass = CountingPass(work = 7L)
        val loop = Inprocessing(listOf(pass), cadence = 100)
        val session = session()
        val params = BacktrackParams(inprocessingInterval = 1L, inprocessingCooldown = 10L)

        session.chargeInprocessingWork(10L)
        loop.onRoot(session, params)
        repeat(20) { loop.onRoot(session, params) }
        assertEquals(1, pass.runs)
        session.chargeInprocessingWork(10L)
        loop.onRoot(session, params)

        assertEquals(2, pass.runs)
    }

    @Test
    fun `clause growth should trigger independently of restart cadence`() {
        val pass = CountingPass()
        val loop = Inprocessing(listOf(pass), cadence = 100)
        val session = session()
        val params = BacktrackParams(inprocessingGrowth = 1)

        loop.onRoot(session, params)
        assertEquals(0, pass.runs)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
        loop.onRoot(session, params)
        loop.onRoot(session, params)

        assertEquals(1, pass.runs)
    }

    @Test
    fun `pass attachments should not earn another growth opportunity`() {
        val pass = CountingPass(grow = true)
        val loop = Inprocessing(listOf(pass), cadence = 100)
        val session = session()
        val params = BacktrackParams(inprocessingGrowth = 1)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)

        repeat(3) { loop.onRoot(session, params) }

        assertEquals(1, pass.runs)
    }

    @Test
    fun `atomic overspend should repay debt before rotating to the next class`() {
        val passes = List(3) { CountingPass(work = 10L) }
        val loop = Inprocessing(passes, cadence = 1)
        val session = session()
        val params = BacktrackParams(inprocessingWork = 5L)

        repeat(3) { loop.onRestart(session, params) }

        assertEquals(listOf(1, 1, 0), passes.map { it.runs })
    }

    @Test
    fun `resetting cursors should retain unpaid work debt`() {
        val pass = CountingPass(work = 10L)
        val loop = Inprocessing(listOf(pass), cadence = 1)
        val session = session()
        val params = BacktrackParams(inprocessingWork = 5L)

        loop.onRestart(session, params)
        loop.reset()
        loop.onRestart(session, params)

        assertEquals(1, pass.runs)
    }

    @Test
    fun `cooldown should withhold restart attempts until search earns work`() {
        val pass = CountingPass(work = 100L)
        val loop = Inprocessing(listOf(pass), cadence = 1)
        val session = session()
        val params = BacktrackParams(inprocessingCooldown = 10L)

        session.chargeInprocessingWork(10L)
        loop.onRestart(session, params)
        repeat(10) { loop.onRestart(session, params) }

        assertEquals(1, pass.runs)
    }

    @Test
    fun `memory pressure should release optional preparation without a restart`() {
        val session = session()
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
        session.addLearnedClause(Clause(intArrayOf(0, 2)), 2)
        val loop = Inprocessing(listOf(Subsumption()), cadence = 1)
        loop.onRestart(session, BacktrackParams(inprocessingSubsumeWork = 1L))
        val before = SharingMemoryObservation(1024L, 128, { false }, {})
        val retained = before.snapshot { loop.retainedMemory(before) }

        val released = loop.releaseRetainedBuffers()
        val after = SharingMemoryObservation(1024L, 128, { false }, {})
        val remaining = after.snapshot { loop.retainedMemory(after) }

        assertTrue(released)
        assertTrue(assertNotNull(retained.completeValues).getValue("ownedPrimitiveBytes") > 0L)
        assertEquals(0L, assertNotNull(remaining.completeValues).getValue("ownedPrimitiveBytes"))
        assertEquals(2, session.learnedClauseCount)
    }

    @Test
    fun `memory pressure should protect an interrupted root attachment`() {
        for (native in listOf(false, true)) {
            val source = Problem(3, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(1)), Clause(intArrayOf(0, 2, 4)),
            ))
            val session = PropagationSession(source.bake(), nativeSat = native)
            val loop = Inprocessing(listOf(RootSimplificationPass()), cadence = 1)
            val params = BacktrackParams(cancellation = Cancellation { session.learnedClauseCount > 0 })
            loop.onRestart(session, params)
            assertTrue(session.fixpointCancelled)
            val sample = SharingMemoryObservation(1024L, 128, { false }, {})
            val retained = sample.snapshot { loop.retainedMemory(sample) }

            val released = loop.releaseRetainedBuffers()

            assertFalse(released)
            assertTrue(assertNotNull(retained.completeValues).getValue("protectedPrimitiveBytes") >= 12L)
            loop.onRoot(session, BacktrackParams())
            assertFalse(session.fixpointCancelled)
            assertEquals(setOf(2, 4), session.learnedClauseLiterals(0).toSet())
        }
    }

    @Test
    fun `memory pressure should preserve completed root facts awaiting publication`() {
        for (native in listOf(false, true)) {
            val source = Problem(2, 0, emptyArray(), arrayOf<Factor>(
                Clause(intArrayOf(1)), Clause(intArrayOf(0, 2)),
            ))
            val session = PropagationSession(source.bake(), nativeSat = native)
            val loop = Inprocessing(listOf(RootSimplificationPass()), cadence = 1)
            val publicationNeeded = loop.onRestart(session, BacktrackParams())
            session.addLearnedClause(Clause(intArrayOf(0, 1)), 9)
            val count = session.learnedClauseCount
            val revision = session.learnedClauseRevision

            val released = loop.releaseRetainedBuffers()
            val reclamation = session.reclaimLearnedMemory(0L, maxWork = 1_000_000L,
                publicationPending = loop.retainsPublication)

            assertTrue(publicationNeeded)
            assertFalse(session.fixpointCancelled)
            assertFalse(released)
            assertEquals(ReclamationStatus.DEFERRED, reclamation.status)
            assertEquals(count, session.learnedClauseCount)
            assertEquals(revision, session.learnedClauseRevision)
            assertEquals(true, session.boolValue(1))
        }
    }
}
