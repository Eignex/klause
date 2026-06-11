package com.eignex.klause.portfolio

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

internal actual fun <T> parallelStream(tasks: List<(emit: (T) -> Unit) -> Unit>): Sequence<T> = sequence {
    val done = Any()
    val queue = LinkedBlockingQueue<Any>()
    val remaining = AtomicInteger(tasks.size)
    val threads = tasks.map { task ->
        Thread {
            try {
                task { item -> queue.put(item as Any) }
            } finally {
                if (remaining.decrementAndGet() == 0) queue.put(done)
            }
        }.apply {
            isDaemon = true
            start()
        }
    }
    while (true) {
        val next = queue.take()
        if (next === done) break
        @Suppress("UNCHECKED_CAST")
        yield(next as T)
    }
    threads.forEach { it.join() }
}

internal actual fun <T> parallelRun(tasks: List<() -> T>): List<T> {
    if (tasks.size == 1) return listOf(tasks[0]())
    val results = arrayOfNulls<Any?>(tasks.size)
    val errors = arrayOfNulls<Throwable>(tasks.size)
    val threads = tasks.mapIndexed { i, task ->
        Thread {
            // Capture any worker failure (so it never silently dies on its thread) and rethrow it
            // on the caller after join — Throwable is deliberate.
            @Suppress("TooGenericExceptionCaught")
            try {
                results[i] = task()
            } catch (e: Throwable) {
                errors[i] = e
            }
        }.apply {
            isDaemon = true
            start()
        }
    }
    threads.forEach { it.join() }
    errors.firstOrNull { it != null }?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return List(tasks.size) { results[it] as T }
}
