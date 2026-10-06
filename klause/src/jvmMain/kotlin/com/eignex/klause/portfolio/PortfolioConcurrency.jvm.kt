package com.eignex.klause.portfolio

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
