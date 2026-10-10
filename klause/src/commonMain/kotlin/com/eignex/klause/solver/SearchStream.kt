package com.eignex.klause.solver

/**
 * A single-consumer search cursor. Request its iterator once, and close it when stopping early.
 * Completion, failure and idempotent [close] release its retained search and resources.
 * A buffered terminal value remains readable after completion; explicit close discards it.
 */
interface SearchStream<out T : Any> : Sequence<T>, Iterator<T>, AutoCloseable {
    /** True when the search has completed, failed or been closed. */
    val isDone: Boolean

    override fun close()
}

@Suppress("TooGenericExceptionCaught") // Cleanup must preserve an arbitrary search failure as primary.
internal class PullSearchStream<T : Any>(
    pull: () -> T?,
    release: () -> Unit = {},
    terminal: (T) -> Boolean = { false },
) : SearchStream<T> {
    private var pull: (() -> T?)? = pull
    private var release: (() -> Unit)? = release
    private var terminal: ((T) -> Boolean)? = terminal
    private var buffered: T? = null
    private var iterated = false

    override val isDone: Boolean get() = pull == null

    override fun iterator(): Iterator<T> {
        check(!iterated) { "a search stream has one consumer" }
        iterated = true
        return this
    }

    override fun hasNext(): Boolean {
        if (buffered != null) return true
        val advance = pull ?: return false
        try {
            val value = advance()
            if (value == null) {
                finish()
                return false
            }
            buffered = value
            if (checkNotNull(terminal)(value)) finish()
            return true
        } catch (failure: Throwable) {
            try {
                close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    override fun next(): T {
        if (!hasNext()) throw NoSuchElementException()
        return checkNotNull(buffered).also { buffered = null }
    }

    private fun finish() {
        pull = null
        terminal = null
        val cleanup = release
        release = null
        cleanup?.invoke()
    }

    override fun close() {
        buffered = null
        finish()
    }
}

internal fun <T : Any> Sequence<T>.asSearchStream(
    release: () -> Unit = {},
    terminal: (T) -> Boolean = { false },
): SearchStream<T> {
    var source: Sequence<T>? = this
    var cursor: Iterator<T>? = null
    return PullSearchStream(
        pull = {
            val iterator = cursor ?: checkNotNull(source).iterator().also {
                cursor = it
                source = null
            }
            if (iterator.hasNext()) iterator.next() else null
        },
        release = {
            source = null
            cursor = null
            release()
        },
        terminal = terminal,
    )
}
