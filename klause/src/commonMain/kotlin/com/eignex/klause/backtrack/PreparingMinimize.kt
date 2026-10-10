package com.eignex.klause.backtrack

import com.eignex.klause.propagation.PropagationPreparation
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.cancelledWhen
import kotlin.time.TimeSource

@Suppress("TooGenericExceptionCaught") // cleanup must preserve arbitrary engine failures
internal class PreparingMinimize(
    private val solver: BacktrackSolver,
    private val objective: LinearObjective,
    private val params: BacktrackParams,
) : ResumableSearch {
    private var globalToken = params.cancellation
    private val propagationToken = cancelledWhen({ globalToken.deadline() }) { globalToken() }
    private var preparation: PropagationPreparation? = PropagationPreparation(
        solver.problem, propagationToken, params.propagationCancelFloor, params.pbLearning ?: true,
    )
    private var search: ResumableMinimize? = null
    private var closed = false
    private var retainedWork = 0L
    private var retainedPropagationNanos = 0L
    private val propagationWork: Long get() = preparation?.work ?: retainedWork
    private val propagationNanos: Long get() = preparation?.propagationNanos ?: retainedPropagationNanos
    private val sink = SolveStatsSink(backend = "backtrack").also {
        it.start()
        it.search.propagationWork = { propagationWork }
        it.search.rootPropagationWork = { propagationWork }
        it.search.propagationNanos = { propagationNanos }
        it.search.rootPropagationNanos = { propagationNanos }
    }
    private val budget = SliceBudget({ 0L }, { 0L }, { propagationWork })

    override val isDone: Boolean get() = search?.isDone == true
    override val preparationPending: Boolean get() = search == null && !closed
    override val stats: SolveStats get() = search?.stats ?: sink.snapshot()
    override val work: Long get() = search?.work ?: budget.spent()

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        search?.let { if (it.isDone) return it.runSlice(global, sliceMillis, sliceNodes, onIncumbent) }
        check(!closed) { "search is closed" }
        globalToken = global
        search?.let { return it.runSlice(global, sliceMillis, sliceNodes, onIncumbent) }
        if (!budget.begin(sliceMillis, sliceNodes)) return null
        val before = work
        try {
            val preparing = checkNotNull(preparation)
            while (!globalToken() && !budget.expired()) {
                val session = preparing.advance()
                budget.charge()
                if (preparing.cancelled) throw SearchInitializationCancelled(stats, work)
                if (session != null) {
                    budget.noteOverspend()
                    val preparedWork = work
                    val prepared = ResumableMinimize(
                        solver, objective, params.copy(cancellation = propagationToken),
                        preparedSession = session, preparationBudget = budget, sink = sink,
                    )
                    search = prepared
                    retainedWork = preparing.work
                    retainedPropagationNanos = preparing.propagationNanos
                    preparation = null
                    val millis = budget.deadline?.let {
                        (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds.coerceAtLeast(0L)
                    } ?: sliceMillis
                    val nodes = if (sliceNodes < 0L) {
                        -1L
                    } else {
                        (sliceNodes - (preparedWork - before)).coerceAtLeast(0L)
                    }
                    return prepared.runSlice(global, millis, nodes, onIncumbent)
                }
            }
            budget.noteOverspend()
            if (globalToken() || preparing.cancelled) throw SearchInitializationCancelled(stats, work)
            return null
        } catch (failure: Throwable) {
            try {
                close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            search?.close()
        } finally {
            sink.stop()
            retainedWork = propagationWork
            retainedPropagationNanos = propagationNanos
            preparation = null
        }
    }
}
