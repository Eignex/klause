package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.ClauseTier
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClauseDbTest {

    @Test
    fun `cap selection and physical cleanup should share one work grant`() {
        for (native in listOf(false, true)) for (tiered in listOf(false, true)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            for (lbd in listOf(9, 8, 7, 1)) session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), lbd)
            val before = session.work

            forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = 2, tieredLearnedDb = tiered,
                learnedMemoryWork = 128L))

            assertTrue(session.work - before <= 128L)
        }
    }

    @Test
    fun `retired slots should not consume full cap survivor quotas`() {
        for (native in listOf(false, true)) for (tiered in listOf(false, true)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            for (lbd in listOf(3, 7, 8, 9, 10)) session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), lbd)
            val capture = session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
                256L, 256L, 1024L) { false }
            session.authorizeLearnedRetirement(assertNotNull(capture.handle), true, 256L, 256L) { false }
            session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)

            forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = 3, tieredLearnedDb = tiered))

            assertEquals(listOf(7, 8, 9), List(session.learnedClauseCount) { session.learnedClauseLbd(it) })
        }
    }

    @Test
    fun `full cap sweep events should count only newly retired rows`() {
        for (native in listOf(false, true)) for (tiered in listOf(false, true)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            for (lbd in listOf(3, 7, 8, 9, 10)) session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), lbd)
            val capture = session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
                256L, 256L, 1024L) { false }
            session.authorizeLearnedRetirement(assertNotNull(capture.handle), true, 256L, 256L) { false }
            session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)
            val events = ArrayList<SearchEvent>()

            forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = 3, tieredLearnedDb = tiered,
                onEvent = { events.add(it) }))

            assertEquals(listOf<SearchEvent>(SearchEvent.LearnedDbSweep(kept = 3, dropped = 1)), events)
        }
    }

    @Test
    fun `retired slot cleanup should preserve tier policy below the active limit`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            for (lbd in listOf(3, 7, 8, 9)) session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), lbd)
            val capture = session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
                256L, 256L, 1024L) { false }
            session.authorizeLearnedRetirement(assertNotNull(capture.handle), true, 256L, 256L) { false }
            session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)
            forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = 3, tieredLearnedDb = true))

            assertEquals(List(3) { ClauseTier.UNSET },
                List(session.learnedClauseCount) { session.learnedClauseTier(it) })
        }
    }

    @Test
    fun `retired slot cleanup should respect the containing work grant`() {
        for (native in listOf(false, true)) for (grant in listOf(0L, 1L, 10L)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            assertEquals(native, session.usesNativeSat)
            for (lbd in listOf(3, 7, 8, 9)) session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), lbd)
            val capture = session.queueLearnedRetirement(0, session.learnedRetirementGeneration,
                256L, 256L, 1024L) { false }
            session.authorizeLearnedRetirement(assertNotNull(capture.handle), true, 256L, 256L) { false }
            session.advanceLearnedRetirements(256L, 256L, { false }, publicationPending = false)
            val before = session.work

            forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = 3, tieredLearnedDb = true,
                learnedMemoryWork = grant))

            assertEquals(4, session.learnedClauseCount)
            assertTrue(session.work - before <= grant)
        }
    }

    @Test
    fun `small cap grants should retain lower LBD and glue priorities`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            for (lbd in listOf(9, 8, 7, 1)) session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), lbd)
            val params = BacktrackParams(maxLearnedClauses = 2, tieredLearnedDb = true, learnedMemoryWork = 64L)

            repeat(32) {
                val before = session.work
                forgetIfOverCap(session, params)
                assertTrue(session.work - before <= 64L)
            }

            assertEquals(2, session.activeLearnedClauseCount)
            assertTrue(session.isLearnedClause(2))
            assertTrue(session.isLearnedClause(3))
        }
    }

    @Test
    fun `appended permanent clauses should reduce a pending cap quota`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            for (lbd in listOf(9, 8, 7)) session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), lbd)
            val params = BacktrackParams(maxLearnedClauses = 2, learnedMemoryWork = 64L)
            forgetIfOverCap(session, params)

            session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 9, permanent = true)
            repeat(32) { forgetIfOverCap(session, params) }

            assertEquals(2, session.activeLearnedClauseCount)
            assertTrue(session.isLearnedClause(2))
            assertTrue(session.isLearnedClause(3))
        }
    }

    @Test
    fun `compaction should invalidate pending cap handles`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            for (lbd in listOf(9, 8, 7)) session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), lbd)
            val params = BacktrackParams(maxLearnedClauses = 2, learnedMemoryWork = 64L)
            forgetIfOverCap(session, params)

            session.forgetLearnedClauses { index, _ -> index != 0 }
            session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 6)
            repeat(32) { forgetIfOverCap(session, params) }

            assertFalse(session.isLearnedClause(0))
            assertTrue(session.isLearnedClause(1))
            assertTrue(session.isLearnedClause(2))
        }
    }

    @Test
    fun `new clauses should join an unfinished ranked window`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            for (lbd in listOf(9, 8, 7)) session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), lbd)
            val params = BacktrackParams(maxLearnedClauses = 2, learnedMemoryWork = 64L)
            forgetIfOverCap(session, params)

            session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 6)
            repeat(32) { forgetIfOverCap(session, params) }

            assertEquals(2, session.activeLearnedClauseCount)
            assertTrue(session.isLearnedClause(2))
            assertTrue(session.isLearnedClause(3))
        }
    }

    @Test
    fun `ranking should preserve protected survivors beside the heap`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            for (lbd in listOf(1, 9, 2, 8, 7, 6)) session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), lbd)
            val params = BacktrackParams(maxLearnedClauses = 3, tieredLearnedDb = true, learnedMemoryWork = 64L)

            repeat(64) { forgetIfOverCap(session, params) }

            assertEquals(3, session.activeLearnedClauseCount)
            for (index in listOf(0, 2, 5)) assertTrue(session.isLearnedClause(index))
        }
    }

    @Test
    fun `use after classification should protect a pending eviction`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(Problem(6, 0, emptyArray(), arrayOf(Clause(intArrayOf(8, 10)))),
                nativeSat = native)
            session.addLearnedClause(Clause(intArrayOf(0, 2)), 9)
            session.addLearnedClause(Clause(intArrayOf(4, 6)), 8)
            repeat(4) { session.addLearnedClause(Clause(intArrayOf(8, 10)), 9, permanent = true) }
            var stop = true
            val params = BacktrackParams(maxLearnedClauses = 5, tieredLearnedDb = true,
                learnedMemoryWork = 128L,
                cancellation = Cancellation { stop && session.learnedLifecycle().getValue("learnedCapVisits") >= 2L })
            forgetIfOverCap(session, params)
            assertEquals(6, session.activeLearnedClauseCount)

            stop = false
            session.pinBool(0, false)
            session.popToLevel(0)
            session.pinBool(5, true)
            session.popToLevel(0)
            repeat(32) { forgetIfOverCap(session, params) }

            assertTrue(session.isLearnedClause(0))
            assertFalse(session.isLearnedClause(1))
        }
    }

    @Test
    fun `general production caps should retire locals above a permanent floor`() {
        for (cap in listOf(4_000, 20_000)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = false)
            repeat(cap) { session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 9, permanent = true) }
            session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 9)
            val before = session.work

            forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = cap, tieredLearnedDb = true))

            assertEquals(cap, session.activeLearnedClauseCount)
            assertEquals(1L, session.learnedLifecycle()["learningClauseRetired"])
            assertTrue(session.work - before <= 65_536L)
        }
    }

    @Test
    fun `pending publication should preserve native cap candidates`() {
        val session = PropagationSession(chainProblem().bake(), nativeSat = true)
        session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 9, permanent = true)
        repeat(9) { session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 9) }
        val before = session.work

        forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = 1, learnedMemoryWork = 100L),
            protectedPublication = true)

        assertEquals(10, session.activeLearnedClauseCount)
        assertEquals(before, session.work)
    }

    @Test
    fun `native production caps should retire locals above a permanent floor`() {
        for (cap in listOf(4_000, 20_000)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = true)
            repeat(cap) { session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 9, permanent = true) }
            session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 9)
            val before = session.work

            forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = cap, tieredLearnedDb = true))

            assertEquals(cap, session.activeLearnedClauseCount)
            assertEquals(1L, session.learnedLifecycle()["learningClauseRetired"])
            assertTrue(session.work - before <= 65_536L)
        }
    }

    @Test
    fun `unavailable cap preparation preserves clauses and tier policy`() {
        for (native in listOf(false, true)) {
            for (tiered in listOf(false, true)) {
                for (mode in listOf("work", "scratch", "cancelled", "scan")) {
                    val session = PropagationSession(chainProblem().bake(), nativeSat = native)
                    repeat(9) { session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 9) }
                    val before = session.work
                    val tiers = List(9) { session.learnedClauseTier(it) }
                    val events = ArrayList<SearchEvent>()
                    val params = BacktrackParams(
                        maxLearnedClauses = 1,
                        tieredLearnedDb = tiered,
                        learnedMemoryWork = if (mode == "work") 0L else 10_000L,
                        learnedMemoryScratchBytes = if (mode == "scratch") 0L else 10_000L,
                        cancellation = Cancellation {
                            mode == "cancelled" || (mode == "scan" && session.work - before >= 4L)
                        },
                        onEvent = { events.add(it) },
                    )

                    forgetIfOverCap(session, params)

                    assertEquals(9, session.learnedClauseCount)
                    assertEquals(tiers, List(9) { session.learnedClauseTier(it) })
                    assertTrue(events.isEmpty())
                    assertTrue(session.work - before <= 4L)
                }
            }
        }
    }

    @Test
    fun `bounded cap selection preserves lower LBD and glue priority`() {
        for (native in listOf(false, true)) {
            for (tiered in listOf(false, true)) {
                val session = PropagationSession(chainProblem().bake(), nativeSat = native)
                for (lbd in listOf(9, 8, 7, 1)) session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), lbd)

                forgetIfOverCap(session, BacktrackParams(maxLearnedClauses = 2, tieredLearnedDb = tiered))

                assertEquals(listOf(7, 1), List(session.learnedClauseCount) { session.learnedClauseLbd(it) })
            }
        }
    }

    @Test
    fun `a demoted mid tier survives until the following reduction`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            for (lbd in listOf(5, 6, 7, 8)) session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), lbd)
            val params = BacktrackParams(maxLearnedClauses = 1, tieredLearnedDb = true)

            forgetIfOverCap(session, params)

            assertEquals(listOf(5, 6), List(session.learnedClauseCount) { session.learnedClauseLbd(it) })
            forgetIfOverCap(session, params)
            assertEquals(5, session.learnedClauseLbd(0))
            assertEquals(1, session.learnedClauseCount)
        }
    }

    // Implication chain a -> b -> c, so a learned (!a | !b | c) vivifies to (!a | c): probing a=true
    // propagates b and c, falsifying !b (dropped) and satisfying c (prefix proven implied).
    private fun chainProblem(): Problem = Problem(
        numBoolVars = 3,
        numIntVars = 0,
        intDomains = emptyArray(),
        factors = arrayOf<Factor>(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, true))),
            Clause(intArrayOf(Lit.make(1, false), Lit.make(2, true))),
        ),
    )

    private fun vivifiedLiterals(nativeSat: Boolean): Set<Int> {
        val baked = chainProblem().bake()
        val session = PropagationSession(baked, nativeSat = nativeSat)
        assertEquals(nativeSat, session.usesNativeSat)
        session.addLearnedClause(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
            lbd = 3,
        )
        vivify(session, BacktrackParams(vivification = true, vivifyBatch = 8), 0)
        assertEquals(1, session.learnedClauseCount)
        return session.learnedClauseLiterals(0).toSet()
    }

    @Test
    fun `vivify should strengthen a learned clause on the general lane`() {
        assertEquals(setOf(Lit.make(0, false), Lit.make(2, true)), vivifiedLiterals(nativeSat = false))
    }

    @Test
    fun `vivify should strengthen a learned clause in the native-SAT arena store`() {
        assertEquals(setOf(Lit.make(0, false), Lit.make(2, true)), vivifiedLiterals(nativeSat = true))
    }

    @Test
    fun `a vivified import should keep the arm it was imported from`() {
        val session = PropagationSession(chainProblem().bake())
        session.addLearnedClause(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
            lbd = 3,
            origin = 2,
        )

        vivify(session, BacktrackParams(vivification = true, vivifyBatch = 8), 0)

        assertEquals(2, session.learnedClauseLiterals(0).size)
        assertEquals(2, session.learnedClauseOrigin(0))
    }

    @Test
    fun `a vivified clause should inherit its parent's LBD`() {
        val baked = chainProblem().bake()
        val session = PropagationSession(baked)
        session.addLearnedClause(
            Clause(intArrayOf(Lit.make(0, false), Lit.make(1, false), Lit.make(2, true))),
            lbd = 1,
        )
        vivify(session, BacktrackParams(vivification = true, vivifyBatch = 8), 0)
        assertEquals(2, session.learnedClauseLiterals(0).size)
        assertEquals(1, session.learnedClauseLbd(0))
    }

    @Test
    fun `reset should release grown vivification metadata and unattached rows`() {
        for (native in listOf(false, true)) {
            val session = PropagationSession(chainProblem().bake(), nativeSat = native)
            repeat(9) { session.addLearnedClause(Clause(intArrayOf(1, 3, 4)), 3) }
            val pass = VivificationPass()
            val empty = SharingMemoryObservation(1024L, 128, { false }, {})
            val baseline = empty.snapshot { pass.retainedMemory(empty, false) }
            val stats = SearchStatsSink()
            var prepared = false
            val cancellation = Cancellation {
                prepared = prepared || (stats.inprocessProbes >= 9L && session.decisionLevel > 0)
                prepared && session.decisionLevel == 0
            }
            pass.run(session, BacktrackParams(vivifyBatch = 9, cancellation = cancellation), stats)
            val before = SharingMemoryObservation(1024L, 128, { false }, {})
            val retained = before.snapshot { pass.retainedMemory(before, false) }

            pass.reset()
            val after = SharingMemoryObservation(1024L, 128, { false }, {})
            val released = after.snapshot { pass.retainedMemory(after, false) }

            val retainedBytes = assertNotNull(retained.completeValues).getValue("ownedPrimitiveBytes")
            val releasedBytes = assertNotNull(released.completeValues).getValue("ownedPrimitiveBytes")
            assertTrue(retainedBytes > releasedBytes)
            assertEquals(assertNotNull(baseline.completeValues).getValue("ownedPrimitiveBytes"), releasedBytes)
            assertEquals(0L, released.completeValues?.getValue("ownedReferenceSlots"))
            assertEquals(9, session.learnedClauseCount)
            assertEquals(0, session.decisionLevel)
            assertFalse(session.fixpointCancelled)
        }
    }
}
