package com.eignex.klause.bench.profile

import com.eignex.klause.util.Cancellation
import java.lang.ref.WeakReference

internal class LinearQueryInputSamples<T : Any> {
    internal data class Sample<T>(val state: T, val units: Long, val transition: Long)
    internal data class Snapshot<T>(
        val samples: List<Sample<T>>,
        val lastRetainedTransition: Long,
        val lastSampleIndex: Int,
        val retainedUnits: Long,
    )

    private val checkpoints = ArrayList<Sample<T>>(MAX_CHECKPOINTS)
    private var checkpointUnits = 0L
    private var lastObserved = WeakReference<T>(null)
    private var last: Sample<T>? = null
    private var lastIsCheckpoint = false
    private var lastRetainedTransition = 0L
    var transitions = 0L
        private set
    var repeatedIdentity = 0L
        private set
    var uncachedWeight = 0L
        private set
    var invalidWeight = 0L
        private set
    var oversizedWeight = 0L
        private set
    var inconsistentWeight = 0L
        private set
    var skippedCheckpoint = 0L
        private set

    fun observe(state: T, units: Long?) {
        if (lastObserved.get() === state) {
            repeatedIdentity = increment(repeatedIdentity)
            return
        }
        // This marker must not retain a rejected or oversized numerical input.
        lastObserved = WeakReference(state)
        transitions = increment(transitions)
        if (units == null) {
            uncachedWeight = increment(uncachedWeight)
            return
        }
        if (units < 0L) {
            invalidWeight = increment(invalidWeight)
            return
        }
        if (units > MAX_STATE_UNITS) {
            oversizedWeight = increment(oversizedWeight)
            return
        }
        val index = checkpointIndex(state)
        val previous = if (index >= 0) checkpoints[index] else last?.takeIf { it.state === state }
        if (previous != null && previous.units != units) {
            inconsistentWeight = increment(inconsistentWeight)
            return
        }
        val sample = previous ?: Sample(state, units, transitions)
        var checkpoint = index >= 0
        if (!checkpoint && transitions and (transitions - 1L) == 0L) {
            if (checkpoints.size < MAX_CHECKPOINTS && checkpointUnits <= CHECKPOINT_UNITS - units) {
                checkpoints += sample
                checkpointUnits += units
                checkpoint = true
            } else {
                skippedCheckpoint = increment(skippedCheckpoint)
            }
        }
        last = sample
        lastIsCheckpoint = checkpoint
        lastRetainedTransition = transitions
    }

    fun snapshot(): Snapshot<T> {
        val samples = checkpoints.toMutableList()
        val latest = last
        val lastIndex = if (latest == null) {
            -1
        } else if (lastIsCheckpoint) {
            checkpointIndex(latest.state)
        } else {
            samples.add(latest)
            samples.lastIndex
        }
        val units = checkpointUnits + if (latest == null || lastIsCheckpoint) 0L else latest.units
        check(samples.size <= MAX_REFERENCES && units in 0L..MAX_TOTAL_UNITS)
        return Snapshot(samples, lastRetainedTransition, lastIndex, units)
    }

    private fun checkpointIndex(state: T): Int {
        var index = 0
        while (index < checkpoints.size) {
            if (checkpoints[index].state === state) return index
            index++
        }
        return -1
    }

    private fun increment(value: Long): Long = if (value == Long.MAX_VALUE) value else value + 1L

    companion object {
        const val MAX_REFERENCES = 12
        const val MAX_TOTAL_UNITS = 2_000_000L
        const val MAX_STATE_UNITS = MAX_TOTAL_UNITS / 2L
        private const val CHECKPOINT_UNITS = MAX_TOTAL_UNITS - MAX_STATE_UNITS
        private const val MAX_CHECKPOINTS = MAX_REFERENCES - 1
    }
}

internal class LinearQueryObservationCancellation(
    private val original: Cancellation,
    private val observe: () -> Unit,
) : Cancellation {
    var observerFailures = 0L
        private set

    override fun isCancelled(): Boolean {
        val stopped = original()
        try {
            observe()
        } catch (_: Throwable) {
            if (observerFailures < Long.MAX_VALUE) observerFailures++
        }
        return stopped
    }

    override fun deadline() = original.deadline()
    override fun workMeter() = original.workMeter()
}
