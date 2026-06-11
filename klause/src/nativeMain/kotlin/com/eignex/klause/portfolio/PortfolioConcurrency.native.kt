@file:OptIn(ExperimentalForeignApi::class)
// TODO: drop this suppression and migrate off kotlin.native.concurrent.Worker once Kotlin/Native
// ships a stable threads replacement (the API is flagged obsolete but has no drop-in successor yet).
@file:Suppress("DEPRECATION")

package com.eignex.klause.portfolio

import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.Mutex
import com.eignex.kumulant.stream.lock
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.usleep
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

/**
 * Native actual: one [Worker] per task (kotlin/native's new memory model lets workers share the
 * `kotlin.concurrent.atomics` state the portfolio coordinates through). The producer runs on the
 * caller and hands the task lambda to the worker; `Future.result` joins. Workers are non-throwing
 * by contract (they return result objects), matching the portfolio's usage.
 */
internal actual fun <T> parallelRun(tasks: List<() -> T>): List<T> {
    if (tasks.size == 1) return listOf(tasks[0]())
    val workers = List(tasks.size) { Worker.start() }
    try {
        // Launch each task on its own worker, then join the futures in order.
        val launched = List(tasks.size) { i ->
            workers[i].execute(TransferMode.SAFE, { tasks[i] }) { it() }
        }
        return launched.map { it.result }
    } finally {
        workers.forEach { it.requestTermination(processScheduledJobs = false) }
    }
}

/** Shared, lock-guarded merge buffer fed by the worker [Worker]s and drained by the caller. */
private class StreamBuffer<T>(val lock: Mutex) {
    val items = ArrayDeque<T>()
    var remaining = 0
}

internal actual fun <T> parallelStream(tasks: List<(emit: (T) -> Unit) -> Unit>): Sequence<T> = sequence {
    val state = StreamBuffer<T>(Concurrency.Strict.lock())
    state.remaining = tasks.size
    val workers = List(tasks.size) { Worker.start() }
    workers.forEachIndexed { i, worker ->
        val task = tasks[i]
        worker.execute(TransferMode.SAFE, { Pair(task, state) }) { (t, s) ->
            try {
                t { item -> s.lock.withLock { s.items.addLast(item) } }
            } finally {
                s.lock.withLock { s.remaining-- }
            }
        }
    }
    try {
        while (true) {
            val item = state.lock.withLock { if (state.items.isEmpty()) null else state.items.removeFirst() }
            if (item != null) {
                yield(item)
                continue
            }
            val finished = state.lock.withLock { state.remaining == 0 && state.items.isEmpty() }
            if (finished) break
            usleep(POLL_MICROS)
        }
    } finally {
        workers.forEach { it.requestTermination(processScheduledJobs = false) }
    }
}

private const val POLL_MICROS: UInt = 1000u // 1ms poll while the merge buffer is empty
