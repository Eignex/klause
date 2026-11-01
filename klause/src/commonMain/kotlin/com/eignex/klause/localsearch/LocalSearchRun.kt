package com.eignex.klause.localsearch

import com.eignex.klause.backtrack.PROPAGATION_WORK_PER_NODE
import com.eignex.klause.backtrack.SliceBudget
import com.eignex.klause.solver.lifetimeCancellation
import com.eignex.klause.solver.resources.LS_INSTRUCTIONS_PER_WORK
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.math.ceil
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal fun interface LocalSearchCheckpoint : WorkMeter {
    fun shouldPause(moves: Long): Boolean

    override fun charge(units: Long) = Unit

    val inspectionVisits: Long get() = 0L
    val repairVisits: Long get() = 0L
    val repairWork: WorkMeter get() = this
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
    private var scoringVisits = 0L
    override var repairVisits: Long = 0L
        private set
    override val inspectionVisits: Long get() = scoringVisits + minOf(repairVisits, Long.MAX_VALUE - scoringVisits)
    override val repairWork = WorkMeter { units ->
        if (units > 0L) repairVisits += minOf(units, Long.MAX_VALUE - repairVisits)
    }
    private val pricedInspections: Long get() = scoringVisits + minOf(
        repairVisits / (BOOLEAN_REPAIR_VISITS_PER_WORK / PROPAGATION_WORK_PER_NODE),
        Long.MAX_VALUE - scoringVisits,
    )
    private var pricedInstructions = 0.0
    private var instructionsPerWork = LS_INSTRUCTIONS_PER_WORK
    private val slice = SliceBudget(
        nodeCount = { pricedInstructions.toLong() },
        lpWork = { 0L },
        propagationWork = { pricedInspections },
    )
    var hasSliceAllowance: Boolean = true
        private set
    val cancellation: Cancellation = Cancellation { token() }
    val work: Long get() = slice.spent()
    val auxiliaryWork: Long get() = pricedInspections / PROPAGATION_WORK_PER_NODE

    override fun charge(units: Long) {
        if (units > 0L) scoringVisits += minOf(units, Long.MAX_VALUE - scoringVisits)
    }

    override fun shouldPause(moves: Long): Boolean {
        pricedInstructions += (moves - instructions).coerceAtLeast(0L) / instructionsPerWork
        instructions = moves
        return moves >= limit || slice.remainingNodes() == 0L
    }

    fun offer(
        global: Cancellation, millis: Long, instructions: Long, available: Long, sliceNodes: Long = -1L,
        instructionsPerWork: Double = LS_INSTRUCTIONS_PER_WORK,
    ) {
        slice.noteOverspend()
        this.instructionsPerWork = instructionsPerWork
        hasSliceAllowance = slice.begin(millis, sliceNodes)
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
        slice.noteOverspend()
        slice.begin(Long.MAX_VALUE, -1L)
    }

    companion object {
        fun instructionAllowance(nodes: Long): Long = if (nodes < 0L) Long.MAX_VALUE
        else ceil(nodes * LS_INSTRUCTIONS_PER_WORK).toLong()
    }
}

internal const val BOOLEAN_REPAIR_VISITS_PER_WORK = 128_000L

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
