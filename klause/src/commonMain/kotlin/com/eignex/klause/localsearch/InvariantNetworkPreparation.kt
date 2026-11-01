package com.eignex.klause.localsearch

import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.stream.Mutex

internal class InvariantNetworkPreparation(
    sweep: DefinitionalSweep?,
    numIntVars: Int,
    numBoolVars: Int,
    private val lock: Mutex = Mutex(),
) {
    private val steps = sweep?.networkPreparation(numIntVars, numBoolVars)?.iterator()
    private var complete = false
    private var network: InvariantNetwork? = null

    val value: InvariantNetwork?
        get() = lock.withLock {
            check(complete) { "the definition network is still being prepared" }
            network
        }

    fun get(cancellation: Cancellation): Boolean {
        while (true) {
            val ready = lock.withLock {
                if (complete) return@withLock true
                if (cancellation()) return@withLock false
                if (steps == null || !steps.hasNext()) {
                    complete = true
                } else {
                    steps.next()?.let {
                        network = it
                        complete = true
                    }
                }
                complete
            }
            if (ready || cancellation()) return ready
        }
    }
}
