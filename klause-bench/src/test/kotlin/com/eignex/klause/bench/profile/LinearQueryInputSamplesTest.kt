package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LinearQueryInputSamplesTest {
    @Test
    fun `power checkpoints and the last admissible transition should fit twelve references`() {
        val samples = LinearQueryInputSamples<Any>()
        repeat(2049) { samples.observe(Any(), 1L) }

        val snapshot = samples.snapshot()

        assertEquals(12, snapshot.samples.size)
        assertEquals(listOf(1L, 2L, 4L, 8L, 16L, 32L, 64L, 128L, 256L, 512L, 1024L, 2049L),
            snapshot.samples.map { it.transition })
        assertEquals(2049L, snapshot.lastRetainedTransition)
        assertEquals(12L, snapshot.retainedUnits)
    }

    @Test
    fun `last replacement should remove the previous unshared storage charge`() {
        val samples = LinearQueryInputSamples<Any>()
        repeat(17) { samples.observe(Any(), LinearQueryInputSamples.MAX_STATE_UNITS) }

        val snapshot = samples.snapshot()

        assertEquals(2, snapshot.samples.size)
        assertEquals(LinearQueryInputSamples.MAX_TOTAL_UNITS, snapshot.retainedUnits)
        assertEquals(17L, snapshot.lastRetainedTransition)
    }

    @Test
    fun `a checkpoint shared with the latest state should be counted once`() {
        val samples = LinearQueryInputSamples<Any>()
        val first = Any()
        samples.observe(first, 7L)
        samples.observe(Any(), 11L)
        samples.observe(first, 7L)

        val snapshot = samples.snapshot()

        assertEquals(2, snapshot.samples.size)
        assertEquals(18L, snapshot.retainedUnits)
        assertEquals(0, snapshot.lastSampleIndex)
        assertEquals(3L, snapshot.lastRetainedTransition)
    }

    @Test
    fun `repeated identities should not become extra observed transitions`() {
        val samples = LinearQueryInputSamples<Any>()
        val state = Any()
        repeat(8) { samples.observe(state, 1L) }

        assertEquals(1L, samples.transitions)
        assertEquals(7L, samples.repeatedIdentity)
        assertEquals(1, samples.snapshot().samples.size)
    }

    @Test
    fun `missing invalid and oversized weights should retain only admissible inputs`() {
        val samples = LinearQueryInputSamples<Any>()
        samples.observe(Any(), 13L)
        samples.observe(Any(), null)
        samples.observe(Any(), -1L)
        samples.observe(Any(), Long.MAX_VALUE)

        val snapshot = samples.snapshot()

        assertEquals(1L, samples.uncachedWeight)
        assertEquals(1L, samples.invalidWeight)
        assertEquals(1L, samples.oversizedWeight)
        assertEquals(13L, snapshot.retainedUnits)
        assertEquals(1L, snapshot.lastRetainedTransition)
        assertEquals(4L, samples.transitions)
    }

    @Test
    fun `an inconsistent weight should not replace a retained input`() {
        val samples = LinearQueryInputSamples<Any>()
        val state = Any()
        samples.observe(state, 13L)
        samples.observe(Any(), 17L)
        samples.observe(state, 19L)

        assertEquals(1L, samples.inconsistentWeight)
        assertEquals(30L, samples.snapshot().retainedUnits)
        assertEquals(2L, samples.snapshot().lastRetainedTransition)
    }

    @Test
    fun `observation should forward each original poll and preserve its stop`() {
        var polls = 0
        var observations = 0
        val original = Cancellation { ++polls >= 2 }
        val stop = LinearQueryObservationCancellation(original) { observations++ }

        assertFalse(stop())
        assertTrue(stop())

        assertEquals(2, polls)
        assertEquals(2, observations)
        assertNull(stop.workMeter())
    }

    @Test
    fun `an original cancellation failure should propagate without observing`() {
        var observations = 0
        val failure = IllegalStateException("source stop failed")
        val stop = LinearQueryObservationCancellation(Cancellation { throw failure }) { observations++ }

        assertSame(failure, assertFailsWith<IllegalStateException> { stop() })
        assertEquals(0, observations)
    }

    @Test
    fun `observer failures should preserve the original stop and meter without charging`() {
        var charged = 0L
        val meter = WorkMeter { charged += it }
        val original = object : Cancellation {
            override fun isCancelled() = true
            override fun workMeter() = meter
        }
        val stop = LinearQueryObservationCancellation(original) { error("observer unavailable") }

        assertTrue(stop())

        assertSame(meter, stop.workMeter())
        assertEquals(0L, charged)
        assertEquals(1L, stop.observerFailures)
    }

    @Test
    fun `the observer should retain the original deadline object`() {
        val original = Cancellation.after(10.seconds)
        val stop = LinearQueryObservationCancellation(original) {}

        assertSame(original.deadline(), stop.deadline())
    }
}
