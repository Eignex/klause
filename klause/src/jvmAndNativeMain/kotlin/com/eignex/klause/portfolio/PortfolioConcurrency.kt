package com.eignex.klause.portfolio

/**
 * Run [tasks] concurrently and return their results in the same order, blocking until all finish.
 * The parallel [Portfolio]'s only concurrency primitive — coroutine-free, real OS threads (JVM) or
 * `Worker`s (native). A single task runs inline (no thread spawn). If any
 * task throws, the first such throwable is rethrown after all tasks have joined.
 *
 * Lives in `jvmAndNativeMain` because js/wasm have no threads; those targets use only the
 * single-threaded [SequentialPortfolio] from `commonMain`.
 */
internal expect fun <T> parallelRun(tasks: List<() -> T>): List<T>

/**
 * Run [tasks] concurrently, each producing items through the `emit` it is given, and return a lazy
 * [Sequence] of the merged items in arrival order — so a [Portfolio] can expose a pull-based stream
 * (anytime incumbents, samples) without a coroutine `Flow` and without a callback in its public API.
 * The sequence yields as items arrive and ends when every task has finished; iterating it drives the
 * tasks. `emit` is thread-safe. Lives in `jvmAndNativeMain` — js/wasm have no threads.
 */
internal expect fun <T> parallelStream(tasks: List<(emit: (T) -> Unit) -> Unit>): Sequence<T>
