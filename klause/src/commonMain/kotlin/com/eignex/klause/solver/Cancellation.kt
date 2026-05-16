package com.eignex.klause.solver

/**
 * Caller-supplied cooperative-cancellation predicate. Engines call it periodically and
 * stop their search promptly when it returns `true`. Defaults are [NeverCancel].
 *
 * Use a simple flag for synchronous cancellation:
 * ```
 * val flag = AtomicBoolean(false)
 * solver.solve(params.copy(cancellation = { flag.get() }))
 * // from another thread: flag.set(true)
 * ```
 *
 * Bridge to coroutine cancellation in one line:
 * ```
 * solver.solve(params.copy(cancellation = { !coroutineContext.isActive }))
 * ```
 *
 * Cancellation is **cooperative**: engines check it between work units (per flip in
 * local search, per decision in backtrack). A request to cancel is observed within a
 * few hundred operations, not instantly.
 *
 * Cancelled `solve` returns [SolveResult.Unknown]. Cancelled `samples` / `enumerate`
 * sequences stop yielding (the consumer sees an early end of stream).
 */
typealias Cancellation = () -> Boolean

/** Default cancellation: never. */
val NeverCancel: Cancellation = { false }
