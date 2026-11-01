package com.eignex.klause.solver.search

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.SharingMemoryObservation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SearchLearningTest {
    @Test
    fun `production count caps should retire eligible clauses within funded windows`() {
        for ((cap, grant) in listOf(4_000 to 512L, 20_000 to 65_536L)) {
            val learning = SearchLearning(SearchLearnedDbParams(maxClauses = cap, glueLbd = 0,
                reclamationWork = grant))
            val host = SearchSession(emptyList())
            host.push(SearchDecision.Bool(0))
            repeat(3) { learning.learn(intArrayOf(0, (it + 1) * 2), host) }
            assertIs<ComponentResult.Consistent>(learning.propagate(host, IntArrayList()))
            repeat(cap) { learning.learn(intArrayOf((it + 4) * 2), host) }

            repeat(40) {
                var charged = 0L
                learning.onRestart({ false }) { charged++ }
                assertTrue(charged <= grant)
            }

            assertEquals(cap, learning.size)
            assertEquals(3L, learning.snapshot().dropped)
        }
    }

    @Test
    fun `count windows should preserve retirement progress across appended clauses`() {
        val learning = SearchLearning(SearchLearnedDbParams(maxClauses = 4, glueLbd = 0,
            reclamationWork = 512L))
        val host = SearchSession(emptyList())
        repeat(10) { learning.learn(intArrayOf(it * 4, it * 4 + 2), host) }
        learning.propagate(host, IntArrayList())

        repeat(20) {
            learning.onRestart()
            learning.learn(intArrayOf((it + 10) * 4, (it + 10) * 4 + 2), host)
            learning.propagate(host, IntArrayList())
        }
        val duringGrowth = learning.snapshot().dropped
        repeat(128) { learning.onRestart() }

        assertTrue(duringGrowth > 0L)
        assertEquals(4, learning.size)
    }

    @Test
    fun `small grants should resume physical release without unrelated ownership changes`() {
        val session = SearchSession(emptyList(), learnedDb = SearchLearnedDbParams(glueLbd = 0,
            softLimitBytes = 0L, reclamationWork = 512L))
        repeat(8) { session.learn(SearchExplanation(intArrayOf(it * 4, it * 4 + 2))) }
        assertIs<ComponentResult.Consistent>(session.propagate())
        val first = requireNotNull(session.reclaimLearningMemory())
        var result = first

        repeat(128) { result = requireNotNull(session.reclaimLearningMemory()) }

        assertEquals(SharedLearningReclamationStatus.Deferred, first.status)
        assertEquals(SharedLearningReclamationStatus.ProtectedFloor, result.status)
        assertEquals(0, session.learnedClauseCount)
        assertTrue(requireNotNull(result.afterBytes) < requireNotNull(first.beforeBytes))
        val sample = SharingMemoryObservation(100_000L, 1024, { false }, {})
        val values = requireNotNull(sample.snapshot { session.retainedLearningMemory(sample) }.completeValues)
        assertEquals(result.afterBytes,
            values.getValue("ownedPrimitiveBytes") + values.getValue("ownedReferenceSlots") * 8L)
        assertEquals(0L, values.getValue("sharedLearnedRetiredObjects"))
    }

    @Test
    fun `a retired clause should be relearnable before its old payload release finishes`() {
        val session = SearchSession(emptyList(), learnedDb = SearchLearnedDbParams(maxClauses = 0,
            glueLbd = 0, reclamationWork = 512L))
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.propagate()
        session.restart()

        session.learn(SearchExplanation(intArrayOf(2, 0)))
        session.push(SearchDecision.Bool(1))

        assertEquals(1, session.learnedClauseCount)
        assertEquals(0L, session.learnedClauseStats().relearned)
        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `cancellation after retirement should retain owners until resumed physical release`() {
        val learning = SearchLearning(SearchLearnedDbParams(maxClauses = 0, glueLbd = 0,
            reclamationWork = 512L))
        val host = SearchSession(emptyList())
        repeat(8) { learning.learn(intArrayOf(it * 4, it * 4 + 2), host) }
        learning.propagate(host, IntArrayList())

        learning.onRestart({ learning.size < 8 })
        val partial = SharingMemoryObservation(100_000L, 1024, { false }, {})
        val partialValues = requireNotNull(partial.snapshot { learning.retainedMemory(partial) }.completeValues)
        repeat(128) { learning.onRestart() }
        val complete = SharingMemoryObservation(100_000L, 1024, { false }, {})
        val completeValues = requireNotNull(complete.snapshot { learning.retainedMemory(complete) }.completeValues)

        assertEquals(1L, partialValues.getValue("sharedLearnedRetiredObjects"))
        assertEquals(0L, completeValues.getValue("sharedLearnedRetiredObjects"))
        assertEquals(0L, completeValues.getValue("sharedLearnedPayloadBytes"))
        assertTrue(completeValues.getValue("sharedLearnedIndexCapacityBytes") <
            partialValues.getValue("sharedLearnedIndexCapacityBytes"))
    }

    @Test
    fun `protected shared clauses should protect their stage and queue allocations`() {
        val session = SearchSession(emptyList(), learnedDb = SearchLearnedDbParams(glueLbd = 0))
        session.learn(SearchExplanation(intArrayOf(0)))
        session.learn(SearchExplanation(intArrayOf(1, 2)))
        session.propagate()
        val sample = SharingMemoryObservation(100_000L, 1024, { false }, {})

        val values = requireNotNull(sample.snapshot { session.retainedLearningMemory(sample) }.completeValues)

        assertEquals(values.getValue("ownedPrimitiveBytes"), values.getValue("protectedPrimitiveBytes"))
        assertEquals(values.getValue("ownedReferenceSlots"), values.getValue("protectedReferenceSlots"))
    }

    @Test
    fun `one work resumed slices should progress after cancelled optional reclamation`() {
        var limited = false
        var spent = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = limited && spent >= 1L
            override fun workMeter(): WorkMeter = WorkMeter { spent += it }
        }
        val session = SearchSession(emptyList(), cancellation = cancellation,
            learnedDb = SearchLearnedDbParams(glueLbd = 0, softLimitBytes = 0L))
        session.initialize()
        session.learn(SearchExplanation(intArrayOf(1)))
        val run = session.openRun(1, lifecycle = object : SearchRunLifecycle {
            override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Pause
        })
        limited = true
        var event: SearchRunEvent = SearchRunEvent.Paused

        repeat(4) {
            if (event is SearchRunEvent.Paused) {
                spent = 0L
                event = run.next()
            }
        }

        assertIs<SearchRunEvent.Satisfied>(event)
        assertEquals(false, session.boolValue(0))
    }

    @Test
    fun `unchanged shared ownership should reuse its completed reclamation attempt`() {
        var charged = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val session = SearchSession(emptyList(), cancellation = cancellation,
            learnedDb = SearchLearnedDbParams(glueLbd = 0, softLimitBytes = 0L))
        val first = session.reclaimLearningMemory()
        val initialWork = charged

        repeat(5) { assertEquals(first, session.reclaimLearningMemory()) }

        assertTrue(initialWork > 0L)
        assertEquals(initialWork, charged)
        session.learn(SearchExplanation(intArrayOf(0)))
        session.reclaimLearningMemory()
        assertTrue(charged > initialWork)
        val learnedWork = charged
        session.imply(2, SearchExplanation(intArrayOf(2)))
        session.reclaimLearningMemory()
        assertTrue(charged > learnedWork)
    }

    @Test
    fun `unchanged shared ownership should reuse a deferred reclamation attempt`() {
        var charged = 0L
        val cancellation = object : Cancellation {
            override fun isCancelled(): Boolean = false
            override fun workMeter(): WorkMeter = WorkMeter { charged += it }
        }
        val session = SearchSession(emptyList(), cancellation = cancellation,
            learnedDb = SearchLearnedDbParams(softLimitBytes = 0L, reclamationWork = 25L))
        val first = requireNotNull(session.reclaimLearningMemory())
        val initialWork = charged

        repeat(5) { assertEquals(first, session.reclaimLearningMemory()) }

        assertEquals(SharedLearningReclamationStatus.Deferred, first.status)
        assertTrue(initialWork > 0L)
        assertEquals(initialWork, charged)
        session.imply(0, SearchExplanation(intArrayOf(0)))
        session.reclaimLearningMemory()
        assertTrue(charged > initialWork)
    }

    @Test
    fun `soft memory reclamation should release shared payload and capacity without a restart`() {
        val session = SearchSession(emptyList(), learnedDb = SearchLearnedDbParams(glueLbd = 0, softLimitBytes = 0L))
        repeat(12) { session.learn(SearchExplanation(intArrayOf(it * 4, it * 4 + 2))) }
        assertIs<ComponentResult.Consistent>(session.propagate())

        val result = requireNotNull(session.reclaimLearningMemory())

        assertEquals(SharedLearningReclamationStatus.ProtectedFloor, result.status)
        assertEquals(0, session.learnedClauseCount)
        assertTrue(requireNotNull(result.afterBytes) < requireNotNull(result.beforeBytes))
        assertEquals(0L, session.learnedClauseStats().restarts)
        assertEquals(12L, session.learnedClauseStats().dropped)
        val sample = SharingMemoryObservation(100_000L, 1024, { false }, {})
        val values = requireNotNull(sample.snapshot { session.retainedLearningMemory(sample) }.completeValues)
        assertEquals(result.afterBytes,
            values.getValue("ownedPrimitiveBytes") + values.getValue("ownedReferenceSlots") * 8L)
    }

    @Test
    fun `tiny reclamation allowances should defer without losing a pending assertion`() {
        listOf(0L to 1_048_576L, 65_536L to 0L).forEach { (work, scratch) ->
            val session = SearchSession(emptyList(), learnedDb = SearchLearnedDbParams(
                glueLbd = 0, softLimitBytes = 0L, reclamationWork = work, reclamationScratchBytes = scratch,
            ))
            session.learn(SearchExplanation(intArrayOf(0)))

            val result = requireNotNull(session.reclaimLearningMemory())
            assertIs<ComponentResult.Consistent>(session.propagate())

            assertEquals(SharedLearningReclamationStatus.Deferred, result.status)
            assertEquals(true, session.boolValue(0))
        }
    }

    @Test
    fun `soft reclamation should protect pending attachments through suspension and resume`() {
        val session = SearchSession(emptyList(), learnedDb = SearchLearnedDbParams(glueLbd = 0, softLimitBytes = 0L))
        session.initialize()
        val run = session.openRun(2)
        run.pauseBeforeDecision = { true }
        assertIs<SearchRunEvent.Paused>(run.next())
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.learn(SearchExplanation(intArrayOf(1)))

        session.reclaimLearningMemory()
        run.pauseBeforeDecision = { false }
        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(false, session.boolValue(0))
        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `soft reclamation should preserve a live reason and allow its sibling undo`() {
        val session = SearchSession(emptyList(), learnedDb = SearchLearnedDbParams(glueLbd = 0, softLimitBytes = 0L))
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.push(SearchDecision.Bool(1))
        val reason = requireNotNull(session.reasonFor(1))

        val retained = requireNotNull(session.reclaimLearningMemory())
        session.popTo(0)
        val retired = requireNotNull(session.reclaimLearningMemory())
        session.push(SearchDecision.Bool(3))

        assertTrue(requireNotNull(retained.protectedBytes) >= reason.literals.size.toLong() * Int.SIZE_BYTES)
        assertTrue(requireNotNull(retired.afterBytes) < requireNotNull(retired.beforeBytes))
        assertEquals(0, session.learnedClauseCount)
        assertEquals(setOf(0, 2), reason.literals.toSet())
        assertEquals(null, session.boolValue(0))
    }

    @Test
    fun `cancelled reclamation preparation should publish no replacement generation`() {
        var cancelAfter = Int.MAX_VALUE
        var polls = 0
        val session = SearchSession(emptyList(), cancellation = Cancellation { ++polls > cancelAfter },
            learnedDb = SearchLearnedDbParams(glueLbd = 0, softLimitBytes = 0L))
        repeat(3) { session.learn(SearchExplanation(intArrayOf(it * 4, it * 4 + 2))) }
        polls = 0
        cancelAfter = 75

        val result = requireNotNull(session.reclaimLearningMemory())

        assertEquals(SharedLearningReclamationStatus.Cancelled, result.status)
        assertEquals(3, session.learnedClauseCount)
        assertEquals(null, session.learnedLifecycle()["learningClauseRetired"])
        cancelAfter = Int.MAX_VALUE
        session.push(SearchDecision.Bool(1))
        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `release should drop shared payload and copied reasons while preserving lifecycle counts`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.push(SearchDecision.Bool(1))
        val lifecycle = session.learnedLifecycle()
        val watchVisits = session.learnedClauseStats().watchVisits

        session.releaseLearning()

        assertEquals(0, session.learnedClauseCount)
        assertEquals(null, session.reasonFor(1))
        assertEquals(lifecycle, session.learnedLifecycle())
        assertEquals(watchVisits, session.learnedClauseStats().watchVisits)
        val sample = SharingMemoryObservation(100_000L, 1024, { false }, {})
        val values = requireNotNull(sample.snapshot { session.retainedLearningMemory(sample) }.completeValues)
        assertEquals(0L, values.getValue("sharedLearnedPayloadBytes"))
        assertEquals(0L, values.getValue("sharedReasonPayloadBytes"))
    }

    @Test
    fun `cancellation should preserve pending learning until the search resumes`() {
        var cancelled = false
        val session = SearchSession(emptyList(), cancellation = Cancellation { cancelled })
        session.initialize()
        val run = session.openRun(1, lifecycle = object : SearchRunLifecycle {
            override fun onCancellation(context: SearchContext): SearchRunDisposition = SearchRunDisposition.Pause
        })
        session.learn(SearchExplanation(intArrayOf(0)))
        cancelled = true
        assertIs<SearchRunEvent.Paused>(run.next())
        assertEquals(null, session.learnedLifecycle()["learningClauseConsumed"])

        cancelled = false
        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(true, session.boolValue(0))
        assertEquals(1L, session.learnedLifecycle()["learningClauseConsumed"])
    }

    @Test
    fun `learned clause should imply its last unfalsified literal`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(0, 2, 4)))

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(2))
    }

    @Test
    fun `learned clause should conflict when propagation falsifies every literal`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.learn(SearchExplanation(intArrayOf(0, 3)))

        val result = assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(1)))

        assertEquals(setOf(0, 3), result.explanation?.literals?.toSet())
    }

    @Test
    fun `learned clause should imply again after its watches are refalsified in another order`() {
        val session = SearchSession(emptyList())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        session.learn(SearchExplanation(intArrayOf(0, 2, 4)))
        assertIs<ComponentResult.Consistent>(session.propagate())
        session.popTo(0)

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(5)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `learned unit clause should be reimplied after the level that learned it is retracted`() {
        val session = SearchSession(emptyList())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))
        session.learn(SearchExplanation(intArrayOf(0)))
        assertIs<ComponentResult.Consistent>(session.propagate())
        session.popTo(0)
        assertEquals(null, session.boolValue(0))

        assertIs<ComponentResult.Consistent>(session.propagate())

        assertEquals(true, session.boolValue(0))
    }

    @Test
    fun `permuted explanation should not enter the database twice`() {
        val session = SearchSession(emptyList())

        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.learn(SearchExplanation(intArrayOf(2, 0)))

        assertEquals(1, session.learnedClauseCount)
    }

    @Test
    fun `restart reduction over the learned database cap should keep glue clauses`() {
        listOf(0 to 1, 1 to 2).forEach { (glueLbd, expectedCount) ->
            val session = SearchSession(
                emptyList(),
                learnedDb = SearchLearnedDbParams(maxClauses = 1, glueLbd = glueLbd),
            )
            session.learn(SearchExplanation(intArrayOf(0, 2)))
            session.learn(SearchExplanation(intArrayOf(4, 6)))
            assertIs<ComponentResult.Consistent>(session.propagate())

            assertIs<ComponentResult.Consistent>(session.restart())

            assertEquals(expectedCount, session.learnedClauseCount, "glueLbd=$glueLbd")
        }
    }

    @Test
    fun `learned clause telemetry should record a reduction at its restart boundary`() {
        val session = SearchSession(
            emptyList(),
            learnedDb = SearchLearnedDbParams(maxClauses = 1, glueLbd = 0),
        )
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.learn(SearchExplanation(intArrayOf(4, 6)))
        assertIs<ComponentResult.Consistent>(session.propagate())

        assertIs<ComponentResult.Consistent>(session.restart())

        val stats = session.learnedClauseStats()
        assertEquals(2L, stats.learned)
        assertEquals(1L, stats.restarts)
        assertEquals(1L, stats.reductions)
        assertEquals(1L, stats.dropped)
        assertEquals(1L, stats.retained)
        assertEquals(2L, stats.peakRetained)
    }

    @Test
    fun `glue should count the distinct learned clauses spanning at most two levels`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(6, 8)))
        session.learn(SearchExplanation(intArrayOf(6, 8)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(3)))
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(5)))

        session.learn(SearchExplanation(intArrayOf(0, 2, 4)))

        assertEquals(1L, session.glueClauseCount)
    }

    @Test
    fun `root fixings should count only the Booleans assigned below every decision`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(0)))
        assertIs<ComponentResult.Consistent>(session.propagate())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(2)))

        assertEquals(1, session.rootFixedCount)
    }

    @Test
    fun `reduction should keep the oldest clause when LBDs tie`() {
        val session = SearchSession(
            emptyList(),
            learnedDb = SearchLearnedDbParams(maxClauses = 1, glueLbd = 0),
        )
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.learn(SearchExplanation(intArrayOf(4, 6)))
        session.learn(SearchExplanation(intArrayOf(8, 10)))
        assertIs<ComponentResult.Consistent>(session.propagate())
        assertIs<ComponentResult.Consistent>(session.restart())

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(1)))

        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `reduction should prefer lower LBD over insertion order`() {
        listOf(3, 10).forEach { levels ->
            val session = SearchSession(
                emptyList(),
                learnedDb = SearchLearnedDbParams(maxClauses = 1, glueLbd = 0),
            )
            for (variable in 0 until levels) {
                assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(variable * 2)))
            }
            session.learn(SearchExplanation(IntArray(levels) { it * 2 }))
            session.learn(SearchExplanation(intArrayOf(levels * 2, (levels + 1) * 2)))
            assertIs<ComponentResult.Consistent>(session.propagate())
            assertIs<ComponentResult.Consistent>(session.restart())

            assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(levels * 2 + 1)))

            assertEquals(true, session.boolValue(levels + 1), "levels=$levels")
        }
    }

    @Test
    fun `lifecycle counts should distinguish duplicate construction and first consumption`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.learn(SearchExplanation(intArrayOf(2, 0)))
        session.push(SearchDecision.Bool(1))
        session.popTo(0)

        session.push(SearchDecision.Bool(1))

        assertEquals(true, session.boolValue(1))
        assertEquals(mapOf("learningClauseConstructed" to 2L, "learningClauseAdmitted" to 1L,
            "learningClauseAsserted" to 1L, "learningClauseConsumed" to 1L), session.learnedLifecycle())
    }

    @Test
    fun `retired shared clauses should leave copied historical reasons intact`() {
        val session = SearchSession(emptyList(), learnedDb = SearchLearnedDbParams(maxClauses = 0, glueLbd = 0))
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.push(SearchDecision.Bool(1))
        val reason = requireNotNull(session.reasonFor(1))
        session.restart()

        session.restart()

        assertEquals(0, session.learnedClauseCount)
        assertEquals(setOf(0, 2), reason.literals.toSet())
        assertEquals(1L, session.learnedLifecycle()["learningClauseRetired"])
    }

    @Test
    fun `a shared nonunit assertion should be restored over an unchanged root premise`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(1)))
        session.propagate()
        session.push(SearchDecision.Bool(4))
        session.learn(SearchExplanation(intArrayOf(0, 2)))
        session.propagate()
        session.popTo(0)

        session.propagate()

        assertEquals(true, session.boolValue(1))
    }

    @Test
    fun `a pending attachment should survive suspension of the actual search run`() {
        val session = SearchSession(emptyList())
        session.initialize()
        val run = session.openRun(1)
        run.pauseBeforeDecision = { true }
        assertIs<SearchRunEvent.Paused>(run.next())
        session.learn(SearchExplanation(intArrayOf(0)))

        run.pauseBeforeDecision = { false }
        assertIs<SearchRunEvent.Satisfied>(run.next())

        assertEquals(true, session.boolValue(0))
        assertEquals(1L, session.learnedLifecycle()["learningClauseConsumed"])
    }
}
