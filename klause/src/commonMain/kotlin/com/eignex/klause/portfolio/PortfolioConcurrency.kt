package com.eignex.klause.portfolio

/**
 * Run [tasks] concurrently and return their results in the same order, blocking until all finish.
 * The [Portfolio]'s lanes run on it — coroutine-free, real OS threads (JVM) or `Worker`s (native). A single
 * task runs inline (no thread spawn). Tasks must not throw: a native worker cannot hand an exception back, so a
 * task records its own failure for the caller to rethrow.
 */
internal expect fun <T> parallelRun(tasks: List<() -> T>): List<T>
