package com.eignex.klause.lp.engine

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.createCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal sealed interface LpSolveStep<out T> {
    data object Paused : LpSolveStep<Nothing>
    class Completed<T>(val value: T) : LpSolveStep<T>
}

internal class LpSolveCursor<T>(
    private val completed: () -> Unit = {},
    operation: suspend LpSolveCursor<T>.() -> T,
) : AutoCloseable {
    private var outcome: Result<T>? = null
    private var continuation: Continuation<Unit>? = operation.createCoroutine(this, object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) {
            outcome = result
            completed()
        }
    })
    private var closed = false
    private var advancing = false
    private var allowance = 0L
    private var workMark = 0L
    private var lastWork = 0L
    private var pause: () -> Boolean = { false }
    val isAdvancing: Boolean get() = advancing
    val work: Long get() = lastWork

    fun advance(workLimit: Long = 0L, shouldPause: () -> Boolean = { false }): LpSolveStep<T> {
        check(!closed) { "LP solve cursor is closed" }
        check(!advancing) { "LP solve cursor is already advancing" }
        require(workLimit >= 0L)
        outcome?.let { return LpSolveStep.Completed(it.getOrThrow()) }
        allowance = workLimit
        workMark = lastWork
        pause = shouldPause
        val pending = checkNotNull(continuation)
        continuation = null
        advancing = true
        try {
            pending.resume(Unit)
        } finally {
            advancing = false
            pause = { false }
        }
        return outcome?.let { LpSolveStep.Completed(it.getOrThrow()) } ?: LpSolveStep.Paused
    }

    fun shouldPause(work: Long): Boolean {
        require(work >= lastWork) { "LP cursor work must be cumulative" }
        lastWork = work
        return (allowance > 0L && work - workMark >= allowance) || pause()
    }

    suspend fun checkpoint(work: Long) {
        if (shouldPause(work)) pauseAt(work)
    }

    suspend fun pauseAt(work: Long) {
        require(work >= lastWork) { "LP cursor work must be cumulative" }
        lastWork = work
        suspendCoroutine<Unit> { continuation = it }
    }

    override fun close() {
        if (closed) return
        check(!advancing) { "LP solve cursor is already advancing" }
        closed = true
        val pending = continuation
        continuation = null
        val stop = CursorClosed()
        pending?.resumeWithException(stop)
        if (pending != null) {
            outcome?.exceptionOrNull()?.takeUnless { it === stop }?.let { throw it }
            stop.suppressedExceptions.firstOrNull()?.let { throw it }
        }
    }

    private class CursorClosed : RuntimeException()
}

internal fun <T> immediateLpSolve(operation: suspend () -> T): T =
    LpSolveCursor<T> { operation() }.use { cursor ->
        when (val step = cursor.advance()) {
            is LpSolveStep.Completed -> step.value
            LpSolveStep.Paused -> error("synchronous LP solve suspended")
        }
    }
