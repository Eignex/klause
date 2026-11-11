@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.solver.incumbent

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/*
 * Completed source checks of one immutable point. A key names the model, objective and policy identities its check
 * read besides the coordinates; consumer restrictions and claimed values stay with each consumer. Only an accepted
 * check is recorded, so an interrupted or refuted one is checked again. Other coordinates make another sample, which
 * starts without receipts.
 */
internal interface WitnessCheckKey {
    fun sameCheck(other: WitnessCheckKey): Boolean
}

internal class WitnessReceipts {
    private val keys = AtomicReference<List<WitnessCheckKey>>(emptyList())

    fun covers(key: WitnessCheckKey): Boolean = keys.load().any { it.sameCheck(key) }

    fun record(key: WitnessCheckKey) {
        while (true) {
            val current = keys.load()
            if (current.any { it.sameCheck(key) }) return
            val next = if (current.size < CAPACITY) current + key else current.drop(1) + key
            if (keys.compareAndSet(current, next)) return
        }
    }

    fun copy(): WitnessReceipts = WitnessReceipts().also { it.keys.store(keys.load()) }

    private companion object {
        // A point meets a handful of consumers; the oldest receipt only costs a repeated check.
        const val CAPACITY = 8
    }
}
