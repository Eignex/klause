package com.eignex.klause.portfolio

import com.eignex.klause.util.ExecutionControl

internal class PoolClauseNeighborhoodObserver(
    private val sink: (String, Long, PoolClauseNeighborhoodObservation) -> Unit,
) {
    var calls = 0L
        private set
    var attempts = 0
        private set
    var completedEligible = 0
        private set
    var omitted = 0L
        private set
    var work = 0L
        private set
    var modeledBytes = 0L
        private set
    var incomplete = 0
        private set
    var noEligible = 0
        private set
    var samplerDeclined = 0
        private set

    fun begin(worker: String, seed: Long, bools: Int, ints: Int, requested: Int,
        stop: ExecutionControl,
    ): Capture? {
        calls++
        if (completedEligible >= 4 || attempts >= 16 || work > 4096L - 32L ||
            modeledBytes > 65_536L - 1024L || stop()) {
            omitted++
            return null
        }
        attempts++
        work += 32L
        modeledBytes += 1024L
        stop.charge(32L)
        return Capture(worker, seed, calls, bools, ints, requested, stop)
    }

    inner class Capture internal constructor(
        private val worker: String,
        private val seed: Long,
        private val call: Long,
        private val bools: Int,
        private val ints: Int,
        private val requested: Int,
        private val stop: ExecutionControl,
    ) {
        private var usedWork = 32L
        private var usedBytes = 1024L
        private var truncated = false
        private var cursor: Long? = null
        private var entries: Array<IntArray?>? = null
        private var recorded = 0
        var supportPassComplete = false
        var samplerCompleted = false
        var selectedIndex = -1

        fun snapshot(value: Long, count: Int) {
            cursor = value
            if (pay(count.toLong(), 32L + count * 8L)) entries = arrayOfNulls(count)
        }

        fun record(index: Int, origin: Int, count: Int, support: IntArray) {
            val target = entries ?: return
            val width = maxOf(0, count)
            // The reservation includes copies and bounded decimal metadata encoding by the observer.
            if (!pay(16L + width * 12L, 64L + (width + 2L) * 12L)) return
            val entry = IntArray(width + 2)
            entry[0] = origin
            entry[1] = count
            for (i in 0 until width) entry[i + 2] = support[i]
            target[index] = entry
            recorded++
        }

        private fun pay(units: Long, bytes: Long): Boolean {
            if (truncated) return false
            if (units > 4096L - work || bytes > 65_536L - modeledBytes || stop()) {
                truncated = true
                return false
            }
            usedWork += units
            usedBytes += bytes
            work += units
            modeledBytes += bytes
            stop.charge(units)
            return true
        }

        fun finish() {
            val values = entries
            val complete = !truncated && values != null && recorded == values.size && supportPassComplete
            if (complete && samplerCompleted && selectedIndex >= 0) completedEligible++
            if (!complete) incomplete++
            if (complete && selectedIndex < 0) noEligible++
            if (!samplerCompleted) samplerDeclined++
            sink(worker, seed, PoolClauseNeighborhoodObservation(call, bools, ints, requested, cursor,
                values, complete, truncated, supportPassComplete, samplerCompleted, selectedIndex,
                usedWork, usedBytes))
        }
    }
}

internal class PoolClauseNeighborhoodObservation(
    val call: Long,
    val bools: Int,
    val ints: Int,
    val requested: Int,
    val cursor: Long?,
    val entries: Array<IntArray?>?,
    val complete: Boolean,
    val truncated: Boolean,
    val supportPassComplete: Boolean,
    val samplerCompleted: Boolean,
    val selectedIndex: Int,
    val work: Long,
    val modeledBytes: Long,
)
