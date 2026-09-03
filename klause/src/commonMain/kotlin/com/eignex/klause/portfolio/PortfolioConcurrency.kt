package com.eignex.klause.portfolio

/**
 * Run [tasks] concurrently and return their results in the same order, blocking until all finish.
 * The parallel [Portfolio]'s only concurrency primitive — coroutine-free, real OS threads (JVM) or
 * `Worker`s (native). A single task runs inline (no thread spawn). If any
 * task throws, the first such throwable is rethrown after all tasks have joined.
 */
internal expect fun <T> parallelRun(tasks: List<() -> T>): List<T>

/**
 * Run [tasks] concurrently, each producing items through the `emit` it is given, and return a lazy
 * [Sequence] of the merged items in arrival order — so a [Portfolio] can expose a pull-based stream
 * (anytime incumbents, samples) without a coroutine `Flow` and without a callback in its public API.
 * The sequence yields as items arrive and ends when every task has finished; iterating it drives the
 * tasks. `emit` is thread-safe.
 *
 * [onProducersFinished] runs on the consumer once every task has finished and whatever it emits is
 * yielded last, which is where a fan-in holding items back on cross-task ordering hands over the ones
 * it can no longer be waiting on. It runs on the consumer's thread, so it needs no synchronisation of
 * its own, and it does not run at all if the consumer abandons the sequence early.
 */
internal expect fun <T> parallelStream(
    tasks: List<(emit: (T) -> Unit) -> Unit>,
    onProducersFinished: ((emit: (T) -> Unit) -> Unit)? = null,
): Sequence<T>

/** Yield what [onProducersFinished] emits. Buffered first: a `yield` cannot cross a non-suspending emit. */
internal suspend fun <T> SequenceScope<T>.yieldTail(onProducersFinished: ((emit: (T) -> Unit) -> Unit)?) {
    if (onProducersFinished == null) return
    val tail = ArrayList<T>()
    onProducersFinished { tail.add(it) }
    yieldAll(tail)
}
