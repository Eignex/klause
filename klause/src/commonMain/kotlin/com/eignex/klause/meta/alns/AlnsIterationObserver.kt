package com.eignex.klause.meta.alns

import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.ExecutionControl

internal class AlnsIterationObserver(private val sink: (AlnsIterationObservation) -> Unit) {
    var calls = 0L
        private set
    var omitted = 0L
        private set
    var work = 0L
        private set
    var modeledBytes = 0L
        private set
    private var owners = 0L
    private var retained = 0

    fun owner(worker: String, seed: Long, bools: Int, ints: Int, reals: Int): Owner {
        require(worker.length <= 128 && worker.all { it in ' '..'~' })
        return Owner(worker, seed, ++owners, bools, ints, reals)
    }

    inner class Owner internal constructor(
        private val worker: String,
        private val seed: Long,
        private val owner: Long,
        private val bools: Int,
        private val ints: Int,
        private val reals: Int,
    ) {
        private var runs = 0L
        fun nextRun(): Long = ++runs

        fun begin(run: Long, iteration: Int, destroy: Int, repair: Int, fraction: Double,
            incumbent: Double, best: Double, allowance: Long, stop: ExecutionControl,
        ): Capture? {
            calls++
            if (retained >= 32 || work > 4096L - 128L || modeledBytes > 65_536L - 2048L || stop()) {
                omitted++
                return null
            }
            retained++
            work += 128L
            modeledBytes += 2048L
            // Prepayment covers the fixed scalar record and its bounded diagnostic encoding.
            stop.charge(128L)
            return Capture(AlnsIterationObservation(worker, seed, owner, run, iteration, bools, ints, reals,
                destroy, repair, fraction, incumbent, best, allowance))
        }
    }

    inner class Capture internal constructor(val observation: AlnsIterationObservation) {
        private var finished = false

        fun recordOutcome(verdict: String, reason: TerminationReason?) {
            observation.verdict = verdict
            observation.reason = reason?.name
        }

        fun finish(endpoint: String) {
            if (finished) return
            finished = true
            observation.endpoint = endpoint
            sink(observation)
        }
    }
}

internal class AlnsIterationObservation(
    val worker: String,
    val seed: Long,
    val owner: Long,
    val run: Long,
    val iteration: Int,
    val bools: Int,
    val ints: Int,
    val reals: Int,
    val destroy: Int,
    val repair: Int,
    val fraction: Double,
    val incumbent: Double,
    val best: Double,
    val allowance: Long,
) {
    var requested: Int? = null
    var effective: Int? = null
    var scopeComplete = false
    var repaired: Double? = null
    var accepted: Boolean? = null
    var newBest: Boolean? = null
    var reward: Double? = null
    var cpNodes = 0L
    var lsMoves = 0L
    var repairNanos: Long? = null
    var verdict: String? = null
    var reason: String? = null
    var endpoint = "Censored"
}
