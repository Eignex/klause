package com.eignex.klause.solver.pipeline

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.CpSatisfactionTraversal
import com.eignex.klause.backtrack.SearchOutcome
import com.eignex.klause.backtrack.SliceBudget
import com.eignex.klause.backtrack.TraversalSlice
import com.eignex.klause.backtrack.searchWork
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.cancelledWhen

internal class QuadraticTraversalCursor(
    problem: BakedProblem,
    params: BacktrackParams,
    private val sink: SolveStatsSink,
    private val nativeWork: () -> Long,
    private val complete: (Sample, Cancellation) -> QuadraticBranchResult,
) : AutoCloseable {
    private val lifetime = params.cancellation
    private var global = lifetime
    private var closed = false
    private var pending: Sample? = null
    private var terminal: SearchOutcome? = null
    private var completionWork = 0L
    private val slice: SliceBudget = SliceBudget({
        sink.search.searchWork + minOf(completionWork, Long.MAX_VALUE - sink.search.searchWork)
    }, { traversal.lpWork() }, { traversal.propagationWork() })
    private val traversal: CpSatisfactionTraversal = CpSatisfactionTraversal(
        problem,
        params.copy(cancellation = cancelledWhen({
            listOfNotNull(global.deadline(), slice.deadline).minOrNull()
        }) { global() || slice.expired() }),
        sink, LpSolveContext.Production,
        propagationCancellation = Cancellation { global() },
        slice = TraversalSlice(pauses = { !global() }, beforeBranch = {
            slice.charge()
            slice.workExpired()
        }),
    )
    val work: Long get() = slice.spent()
    val isDone: Boolean get() = terminal != null

    fun advance(
        cancellation: Cancellation,
        sliceMillis: Long,
        sliceNodes: Long,
        onBranch: (QuadraticBranchResult) -> Boolean,
    ): SearchOutcome? {
        check(!closed) { "quadratic traversal cursor is closed" }
        terminal?.let { return it }
        global = lifetime or cancellation
        if (!traversal.hasRootOutcome && !slice.begin(sliceMillis, sliceNodes)) return null
        traversal.fixedCancellationCadence = slice.workBounded
        while (!global()) {
            val sample = pending
            if (sample != null) {
                if (slice.expired()) {
                    slice.noteOverspend()
                    return null
                }
                val before = nativeWork()
                val result = try {
                    complete(sample, global)
                } finally {
                    val spent = (nativeWork() - before).coerceAtLeast(0)
                    completionWork += minOf(spent, Long.MAX_VALUE - completionWork)
                }
                pending = null
                if (!onBranch(result)) return null
            }
            when (val event = traversal.next()) {
                is SearchOutcome.Found -> pending = event.sample
                is SearchOutcome.Exhausted, SearchOutcome.BudgetCapped -> {
                    terminal = event
                    return event
                }
                null -> {
                    slice.noteOverspend()
                    return null
                }
            }
        }
        terminal = SearchOutcome.BudgetCapped
        return terminal
    }

    override fun close() {
        if (closed) return
        closed = true
        pending = null
        traversal.close()
    }
}
