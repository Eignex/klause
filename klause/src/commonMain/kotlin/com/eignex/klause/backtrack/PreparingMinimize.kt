package com.eignex.klause.backtrack

import com.eignex.klause.propagation.PropagationPreparation
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.ReseedGuard
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.lifetimeCancellation
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.cancelledWhen
import com.eignex.klause.util.workScheduled
import kotlin.time.TimeSource

@Suppress("TooGenericExceptionCaught") // cleanup must preserve arbitrary engine failures
internal class PreparingMinimize(
    private val solver: BacktrackSolver,
    private val objective: LinearObjective,
    private val params: BacktrackParams,
) : ResumableSearch, ReseedGuard {
    private var globalToken = params.cancellation
    private val propagationToken = cancelledWhen({ globalToken.deadline() }, { globalToken.workScheduled() }) {
        globalToken()
    }
    private var preparation: PropagationPreparation? = PropagationPreparation(
        solver.problem, propagationToken, params.propagationCancelFloor,
        params.pbLearning ?: (solver.problem.numIntVars == 0),
        cumulativeUnarySubsets = solver.problem.numRealVars == 0 && params.useCumulativeUnarySubsets,
        attribution = params.propagationAttribution,
        historicalTables = params.historicalTableReasons,
        costAware = params.costAwarePropagation,
        reasonPreflight = params.reasonPreflight,
    )
    private var search: ResumableMinimize? = null
    private var installing: ResumableMinimize? = null
    private var terminal: MinimizeResult? = null
    private var closed = false
    private var retainedWork = 0L
    private var retainedPropagationNanos = 0L
    private val propagationWork: Long get() = preparation?.work ?: retainedWork
    private val propagationNanos: Long get() = preparation?.propagationNanos ?: retainedPropagationNanos
    private val sink = SolveStatsSink(backend = "backtrack").also {
        it.start()
        it.search.learning = { preparation?.learning() ?: emptyMap() }
        it.search.propagationWork = { propagationWork }
        it.search.rootPropagationWork = { propagationWork }
        it.search.propagationNanos = { propagationNanos }
        it.search.rootPropagationNanos = { propagationNanos }
    }
    private val budget = SliceBudget({ 0L }, { 0L }, { propagationWork })

    override val canReseed: Boolean get() = !preparationPending && search?.canReseed != false
    override val canContinuePreparation: Boolean get() = search?.canContinuePreparation == true

    override val isDone: Boolean get() = terminal != null || search?.isDone == true
    override val preparationPending: Boolean get() = search == null && !closed
    override val stats: SolveStats get() = search?.stats ?: sink.snapshot()
    override val work: Long get() = search?.work ?: budget.spent()

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        terminal?.let { return it }
        search?.let { if (it.isDone) return it.runSlice(global, sliceMillis, sliceNodes, onIncumbent) }
        check(!closed) { "search is closed" }
        globalToken = params.cancellation.lifetimeCancellation() or global
        search?.let { return it.runSlice(global, sliceMillis, sliceNodes, onIncumbent) }
        if (!budget.begin(sliceMillis, sliceNodes)) return null
        val before = work
        try {
            val preparing = checkNotNull(preparation)
            while (!globalToken() && !budget.expired()) {
                val prepared = installing
                if (prepared != null) {
                    val ready = prepared.advanceInstallation()
                    budget.charge()
                    if (!ready) continue
                    budget.noteOverspend()
                    prepared.inheritPreparationBudget(budget)
                    val preparedWork = work
                    search = prepared
                    installing = null
                    retainedWork = preparing.work
                    retainedPropagationNanos = preparing.propagationNanos
                    preparation = null
                    if (globalToken() || budget.expired()) return null
                    val millis = budget.deadline?.let {
                        (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds.coerceAtLeast(0L)
                    } ?: sliceMillis
                    val nodes = if (sliceNodes < 0L) -1L else {
                        (sliceNodes - (preparedWork - before)).coerceAtLeast(0L)
                    }
                    return prepared.runSlice(global, millis, nodes, onIncumbent)
                }
                val session = preparing.advance()
                budget.charge()
                if (preparing.isUnsat) {
                    budget.noteOverspend()
                    sink.stop()
                    terminal = if (params.objectiveBoundSupplier != null) {
                        MinimizeResult.Unknown(TerminationReason.SearchExhausted, sink.snapshot())
                    } else {
                        val core = (solver.problem.baked as? PropagationResult.Unsat)?.let(::coreOf)
                        MinimizeResult.Infeasible(core, sink.snapshot())
                    }
                    close()
                    return terminal
                }
                if (session != null) {
                    installing = ResumableMinimize(
                        solver, objective, params.withCancellation(propagationToken),
                        preparedSession = session, preparationBudget = budget, sink = sink,
                        deferredInstallation = true,
                    )
                }
            }
            budget.noteOverspend()
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
        val pending = search == null
        val completedWork = sink.search.propagationWork()
        val completedRootWork = sink.search.rootPropagationWork()
        val completedNanos = sink.search.propagationNanos()
        val completedRootNanos = sink.search.rootPropagationNanos()
        val completedLearning = sink.search.learning()
        try {
            (search ?: installing)?.close()
        } finally {
            sink.stop()
            retainedWork = propagationWork
            retainedPropagationNanos = propagationNanos
            preparation = null
            installing = null
            if (pending) {
                sink.search.learning = { completedLearning }
                sink.search.propagationWork = { completedWork }
                sink.search.rootPropagationWork = { completedRootWork }
                sink.search.propagationNanos = { completedNanos }
                sink.search.rootPropagationNanos = { completedRootNanos }
            }
        }
    }
}
