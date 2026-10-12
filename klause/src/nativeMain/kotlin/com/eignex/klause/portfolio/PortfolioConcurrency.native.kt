// Portfolio tasks use the Kotlin/Native Workers API.
@file:OptIn(ObsoleteWorkersApi::class)

package com.eignex.klause.portfolio

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

/**
 * Native actual: one [Worker] per task (Kotlin/Native's memory model lets workers share the
 * `kotlin.concurrent.atomics` state the portfolio coordinates through). The producer runs on the
 * caller and hands the task lambda to the worker; `Future.result` joins. Tasks must not throw:
 * a worker cannot hand an exception back to the caller.
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
