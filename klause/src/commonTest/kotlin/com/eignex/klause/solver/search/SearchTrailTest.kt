package com.eignex.klause.solver.search

import com.eignex.klause.util.SharingMemoryObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchTrailTest {
    @Test
    fun `copied root reasons should fit the default memory observation grant`() {
        val session = SearchSession(emptyList())
        repeat(384) { variable ->
            session.imply(variable * 2, SearchExplanation(intArrayOf(variable * 2)))
        }
        val sample = SharingMemoryObservation(SharingMemoryObservation.DEFAULT_WORK,
            SharingMemoryObservation.DEFAULT_SCRATCH_ENTRIES, { false }, {})

        val memory = sample.snapshot { session.retainedLearningMemory(sample) }

        val values = requireNotNull(memory.completeValues)
        assertEquals(384L * Int.SIZE_BYTES, values.getValue("sharedReasonPayloadBytes"))
        assertTrue(memory.work <= SharingMemoryObservation.DEFAULT_WORK)
    }

    @Test
    fun `root reseeding should release obsolete copied reason payload and index capacity`() {
        val session = SearchSession(emptyList())
        repeat(24) { variable ->
            session.imply(variable * 2, SearchExplanation(intArrayOf(variable * 2)))
        }
        val beforeSample = SharingMemoryObservation(100_000L, 1024, { false }, {})
        val before = requireNotNull(beforeSample.snapshot {
            session.retainedLearningMemory(beforeSample)
        }.completeValues)

        session.resetRootFacts()

        val afterSample = SharingMemoryObservation(100_000L, 1024, { false }, {})
        val after = requireNotNull(afterSample.snapshot { session.retainedLearningMemory(afterSample) }.completeValues)
        assertEquals(0L, after.getValue("sharedReasonPayloadBytes"))
        assertTrue(after.getValue("sharedReasonIndexCapacityBytes") < before.getValue("sharedReasonIndexCapacityBytes"))
        assertNull(session.reasonFor(0))
    }

    @Test
    fun `rebuilding the root should discard facts from the previous seed`() {
        val session = SearchSession(emptyList())
        session.publish(SearchDecision.Bool(0))
        session.publish(SearchDecision.IntAtLeast(0, 4L))
        session.publish(SearchDecision.IntAtMost(0, 9L))
        assertIs<ComponentResult.Consistent>(session.propagate())

        session.resetRootFacts()

        assertNull(session.boolValue(0))
        assertNull(session.intLowerBound(0))
        assertNull(session.intUpperBound(0))
        assertEquals(0, session.rootFixedCount)
        session.publish(SearchDecision.IntEqual(0, 2L))
        assertIs<ComponentResult.Consistent>(session.propagate())
        assertEquals(2L, session.intLowerBound(0))
        assertEquals(2L, session.intUpperBound(0))
    }

    @Test
    fun `integer facts should retract across Boolean-only levels`() {
        val session = SearchSession(emptyList())
        session.publish(SearchDecision.IntAtLeast(0, 4L))
        session.push(SearchDecision.Bool(0))
        session.push(SearchDecision.Bool(2))
        session.publish(SearchDecision.IntAtMost(0, 9L))
        session.publish(SearchDecision.IntAtMost(0, 7L))
        session.push(SearchDecision.Bool(4))

        session.popTo(1)

        assertEquals(4L, session.intLowerBound(0))
        assertNull(session.intUpperBound(0))
        assertEquals(true, session.boolValue(0))
        assertNull(session.boolValue(1))
        assertNull(session.boolValue(2))
    }

    @Test
    fun `rebuilding root facts should retain and wake learned units`() {
        val session = SearchSession(emptyList())
        session.learn(SearchExplanation(intArrayOf(0)))
        assertIs<ComponentResult.Consistent>(session.propagate())
        assertEquals(true, session.boolValue(0))

        session.resetRootFacts()
        assertIs<ComponentResult.Consistent>(session.propagate())

        assertEquals(1, session.learnedClauseCount)
        assertEquals(true, session.boolValue(0))
        assertEquals(1, session.rootFixedCount)
    }
}
