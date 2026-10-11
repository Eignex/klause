package com.eignex.klause.localsearch

import com.eignex.klause.backtrack.LS_INSTRUCTIONS_PER_WORK
import com.eignex.klause.solver.lifetimeCancellation
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal fun interface LocalSearchCheckpoint {
    fun shouldPause(moves: Long): Boolean
}

internal class LocalSearchRunControl(private val lifetime: Cancellation) : LocalSearchCheckpoint {
    var token: Cancellation = Cancellation.Never
        private set
    var instructions: Long = 0L
        private set
    var limit: Long = Long.MAX_VALUE
        private set
    var finished: Boolean = false
        private set
    val cancellation: Cancellation = Cancellation { token() }
    val work: Long get() = (instructions / LS_INSTRUCTIONS_PER_WORK).toLong()

    override fun shouldPause(moves: Long): Boolean {
        instructions = moves
        return moves >= limit
    }

    fun offer(global: Cancellation, millis: Long, instructions: Long, available: Long) {
        token = if (millis == Long.MAX_VALUE) {
            lifetime.lifetimeCancellation() or global
        } else {
            lifetime.lifetimeCancellation() or global or
                Cancellation.until(TimeSource.Monotonic.markNow() + millis.milliseconds)
        }
        limit = this.instructions + minOf(instructions, available, Long.MAX_VALUE - this.instructions)
    }

    fun finishStream() {
        finished = true
    }

    fun allowCompletion() {
        limit = Long.MAX_VALUE
    }

    companion object {
        fun instructionAllowance(nodes: Long): Long = if (nodes < 0L) Long.MAX_VALUE
        else ceil(nodes * LS_INSTRUCTIONS_PER_WORK).toLong()
    }
}

internal abstract class LocalSearchRun<V>(
    protected val sink: SolveStatsSink,
    protected val control: LocalSearchRunControl,
    private val release: () -> Unit,
) : AutoCloseable {
    protected var verdict: V? = null
        private set
    protected var closed: Boolean = false
        private set
    val isDone: Boolean get() = verdict != null
    val stats: SolveStats get() = sink.snapshot()

    protected fun finish(result: V): V {
        verdict = result
        discardCursor()
        release()
        return result
    }

    protected fun checkOpen() {
        check(!closed) { "the local-search handle is closed" }
    }

    protected abstract fun discardCursor()

    override fun close() {
        if (closed) return
        closed = true
        discardCursor()
        if (verdict == null) sink.stop()
        release()
    }
}
