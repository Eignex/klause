package com.eignex.klause.backtrack

import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.SearchInitializationCancelled
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.lifetimeCancellation
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.cancelledWhen
import com.eignex.klause.util.workScheduled

/**
 * The satisfaction driver for a single-threaded portfolio: one [CpSatisfactionTraversal] advanced a slice at a
 * time. A slice that ends pauses the traversal at decision granularity and keeps every learned clause, the trail
 * and the heuristics, so the arm resumes where it stopped instead of re-deriving them.
 *
 * Two tokens stop it, as in [ResumableMinimize]. The run's own token, handed to each [runSlice], ends the search;
 * root preparation polls that token. Shared decision assertions also poll the slice allowance; an
 * interrupted assertion retracts to its completed parent and retries the same alternative on resumption.
 */
@Suppress("TooGenericExceptionCaught") // ownership boundaries preserve arbitrary primary and cleanup failures
internal class ResumableSatisfaction(
    private val solver: BacktrackSolver,
    params0: BacktrackParams,
    ownsWorkDebt: Boolean = true,
    projectAssumptionCore: Boolean = false,
    preparedSession: PropagationSession? = null,
    preparedSeed: PropagationResult? = null,
    private val rebindable: Boolean = false,
    atRoot: ((PropagationSession) -> Boolean)? = null,
) : ResumableSolve {
    private val lifetimeToken = params0.cancellation.lifetimeCancellation()
    private var globalToken: Cancellation = params0.cancellation
    private val sink = SolveStatsSink(backend = "backtrack").also { it.start() }

    // Its LP reading waits for the traversal below; nothing arms the slice before the first [runSlice].
    private val slice: SliceBudget = SliceBudget(
        { sink.search.searchWork },
        { traversal.lpWork() },
        { traversal.propagationWork() },
        ownsWorkDebt = ownsWorkDebt,
    )
    private val params = params0.copy(cancellation = cancelledWhen({
        listOfNotNull(globalToken.deadline(), slice.deadline).minOrNull()
    }, { globalToken.workScheduled() }) { globalToken() || slice.expired() })
    private var assumptions = params0.assumptions
    private val traversal: CpSatisfactionTraversal = CpSatisfactionTraversal(
        solver.problem,
        params,
        sink,
        solver.lpSolveContext,
        propagationCancellation = cancelledWhen({ globalToken.deadline() }, { globalToken.workScheduled() }) {
            globalToken()
        },
        projection = preparedSession?.projection ?: solver.propagationProjection(params.nativeSat ?: true),
        deferredInitialization = true,
        singleModel = true,
        projectAssumptionCore = projectAssumptionCore,
        preparedSession = preparedSession,
        preparedSeed = preparedSeed,
        rebindable = rebindable,
        slice = TraversalSlice(pauses = { !globalToken() }, atRoot = atRoot, beforeBranch = {
            slice.charge()
            slice.workExpired()
        }),
    )

    private var done: SolveResult? = null
    private var closed = false

    override val isDone: Boolean get() = done != null
    internal val preparationPending: Boolean get() = traversal.preparationPending

    override val stats: SolveStats get() = sink.snapshot()

    override val work: Long get() = slice.spent()

    override val initialWork: Long = slice.spent()

    init {
        try {
            if (globalToken() || traversal.initializationCancelled) {
                throw SearchInitializationCancelled(stats, work)
            }
        } catch (failure: Throwable) {
            closeAfter(failure)
            throw failure
        }
    }

    internal fun splitLiteral(): Int? = traversal.splitLiteral()

    internal fun rebind(assumptions: Assumptions, cancellation: Cancellation) {
        check(rebindable && !closed)
        try {
            globalToken = lifetimeToken or cancellation
            slice.begin(Long.MAX_VALUE, -1L)
            traversal.rebind(assumptions)
            this.assumptions = assumptions
            done = null
        } catch (failure: Throwable) {
            closeAfter(failure)
            throw failure
        }
    }

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? {
        done?.let { return it }
        check(!closed) { "search is closed" }
        globalToken = lifetimeToken or global
        if (!traversal.hasRootOutcome && !slice.begin(sliceMillis, sliceNodes)) return null
        traversal.fixedCancellationCadence = slice.workBounded
        try {
            val outcome = traversal.next()
            if (outcome == null) {
                slice.noteOverspend()
                // Restarts sample root fixings too, but an arm that restarts rarely would show none of its own.
                sink.search.observeRootFixed(traversal.rootFixedVariableCount())
                return null
            }
            return solver.verdictOf(
                outcome, assumptions, Cancellation { globalToken() }, sink, finish = !rebindable,
            ).also {
                done = it
                if (!rebindable) close()
            }
        } catch (failure: Throwable) {
            closeAfter(failure)
            throw failure
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (done == null || rebindable) sink.stop()
        traversal.close()
    }

    private fun closeAfter(failure: Throwable) {
        try {
            close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
    }
}
