package com.eignex.klause.solver

import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.util.Cancellation

@Suppress("TooGenericExceptionCaught") // The search failure remains primary when releasing ownership also fails.
private class HandleOwnership<T : Any>(private val source: AutoCloseable, private val release: () -> Unit) {
    private var closed = false
    private var terminal: T? = null

    fun advance(action: () -> T?): T? {
        terminal?.let { return it }
        check(!closed) { "search handle is closed" }
        try {
            return action()?.also {
                terminal = it
                close()
            }
        } catch (failure: Throwable) {
            try {
                close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    fun close() {
        if (closed) return
        closed = true
        try {
            source.close()
        } finally {
            release()
        }
    }
}

private open class OwnedSearch(protected val source: ResumableSearch, release: () -> Unit) : ResumableSearch by source {
    private val ownership = HandleOwnership<MinimizeResult>(source, release)

    protected fun advance(action: () -> MinimizeResult?): MinimizeResult? = ownership.advance(action)

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? = advance { source.runSlice(global, sliceMillis, onIncumbent) }

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? = advance { source.runSlice(global, sliceMillis, sliceNodes, onIncumbent) }

    override fun close() = ownership.close()
}

private class OwnedInstructionSearch(private val instructionSource: InstructionSlicedSearch, release: () -> Unit) :
    OwnedSearch(instructionSource, release), InstructionSlicedSearch {
    override fun runInstructionSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceInstructions: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? = advance {
        instructionSource.runInstructionSlice(global, sliceMillis, sliceInstructions, onIncumbent)
    }
}

internal fun ownSearch(source: ResumableSearch, release: () -> Unit): ResumableSearch =
    if (source is InstructionSlicedSearch) OwnedInstructionSearch(source, release) else OwnedSearch(source, release)

private open class OwnedSolve(protected val source: ResumableSolve, release: () -> Unit) : ResumableSolve by source {
    private val ownership = HandleOwnership<SolveResult>(source, release)

    protected fun advance(action: () -> SolveResult?): SolveResult? = ownership.advance(action)

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? =
        advance { source.runSlice(global, sliceMillis, sliceNodes) }

    override fun close() = ownership.close()
}

private class OwnedInstructionSolve(private val instructionSource: InstructionSlicedSolve, release: () -> Unit) :
    OwnedSolve(instructionSource, release), InstructionSlicedSolve {
    override fun runInstructionSlice(global: Cancellation, sliceMillis: Long, sliceInstructions: Long): SolveResult? =
        advance { instructionSource.runInstructionSlice(global, sliceMillis, sliceInstructions) }
}

internal fun ownSolve(source: ResumableSolve, release: () -> Unit): ResumableSolve =
    if (source is InstructionSlicedSolve) OwnedInstructionSolve(source, release) else OwnedSolve(source, release)
