package com.eignex.klause.backtrack

import com.eignex.klause.propagation.PropagationPreparation
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.ResumableSolve
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.lifetimeCancellation
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.withLifetime
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.cancelledWhen
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal class PreparingSatisfaction(
    private val solver: BacktrackSolver,
    private val params: BacktrackParams,
    private val projectAssumptionCore: Boolean = false,
) : ResumableSolve {
    private val lifetime = params.cancellation.lifetimeCancellation()
    private var token = params.cancellation
    private val propagationToken = cancelledWhen({ token.deadline() }) { token() }
    private var preparation: PropagationPreparation? = PropagationPreparation(
        solver.problem, propagationToken, params.propagationCancelFloor,
        params.pbLearning ?: (solver.problem.numIntVars == 0),
        cumulativeUnarySubsets = solver.problem.numRealVars == 0 && params.useCumulativeUnarySubsets,
    )
    private var session: PropagationSession? = null
    private var seeding: Iterator<PropagationResult?>? = null
    private var seed: PropagationResult? = null
    private var query: ResumableSatisfaction? = null
    private var closed = false
    private var retainedStats = SolveStats.EMPTY
    private var retainedWork = 0L
    private val sink = SolveStatsSink(backend = "backtrack").also { it.start() }
    private val propagationWork: Long get() = session?.work ?: preparation?.work ?: 0L

    init {
        sink.search.propagationWork = { propagationWork }
        sink.search.rootPropagationWork = { session?.rootWork ?: preparation?.work ?: 0L }
        sink.search.propagationNanos = { session?.propagationNanos ?: preparation?.propagationNanos ?: 0L }
    }

    override val initialWork: Long = 0L
    override val work: Long get() = if (closed) retainedWork else query?.work ?: propagationWork / PROPAGATION_WORK_PER_NODE
    override val stats: SolveStats get() = if (closed) retainedStats else query?.stats ?: sink.snapshot()
    override val isDone: Boolean get() = query?.isDone == true
    internal val preparationPending: Boolean get() = query == null

    override fun runSlice(global: Cancellation, sliceMillis: Long, sliceNodes: Long): SolveResult? {
        query?.let {
            token = lifetime or global
            return it.runSlice(global, sliceMillis, sliceNodes)
        }
        check(!closed) { "query is closed" }
        if (sliceNodes == 0L) return null
        val deadline = TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
        val start = work
        token = lifetime or global or Cancellation.until(deadline) or Cancellation {
            sliceNodes >= 0L && work - start >= sliceNodes
        }
        while (!token()) {
            val live = session
            if (live == null) {
                session = checkNotNull(preparation).advance()
                continue
            }
            if (seed == null) {
                val builder = seeding ?: live.prepareSeed(params.assumptions).also { seeding = it }
                seed = builder.next()
                continue
            }
            val handle = ResumableSatisfaction(
                solver, params.copy(cancellation = propagationToken.withLifetime(lifetime)),
                ownsWorkDebt = false, projectAssumptionCore = projectAssumptionCore,
                preparedSession = live, preparedSeed = seed,
            )
            query = handle
            preparation = null
            seeding = null
            val remaining = if (sliceNodes < 0L) -1L else (sliceNodes - (work - start)).coerceAtLeast(0L)
            val millis = (deadline - TimeSource.Monotonic.markNow()).inWholeMilliseconds.coerceAtLeast(0L)
            token = lifetime or global
            return handle.runSlice(global, millis, remaining)
        }
        return null
    }

    override fun close() {
        if (closed) return
        retainedWork = work
        retainedStats = stats
        closed = true
        try {
            query?.close()
        } finally {
            sink.stop()
            preparation = null
            session = null
            seeding = null
        }
    }
}
