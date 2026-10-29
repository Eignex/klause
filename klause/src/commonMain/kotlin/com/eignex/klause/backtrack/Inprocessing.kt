package com.eignex.klause.backtrack

import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation

/**
 * One scheduled in-search simplification pass over the live constraint database. Passes run
 * per arm at a safe root boundary, with the session at the post-seed
 * root; each call works a bounded slice so a single restart never stalls on a full database sweep.
 *
 * Every pass must declare whether it preserves the variable set. The portfolio shares learned
 * clauses across arms under globally stable variable ids, and the native-SAT lane shares its base
 * clause arena read-only across arms — a variable-eliminating pass (BVE) in one arm would break
 * both contracts, so [Inprocessing] rejects such passes at construction. Variable-eliminating
 * simplification runs in presolve, once, before the portfolio fork.
 */
internal interface InprocessingPass {
    /** True when the pass never adds, removes, or renames variables — the portfolio-safe category. */
    val preservesVariables: Boolean
    val spending: InprocessingSpending? get() = null

    /** Run one bounded slice with [session] at the post-seed root, leaving it there, and count the work it
     *  does into [stats] so a work-sliced search is charged for it. */
    fun run(session: PropagationSession, params: BacktrackParams, stats: SearchStatsSink?)

    /** Drop per-search cursors; called when the engine reseeds onto a fragment. */
    fun reset()
}

/**
 * Per-arm safe-root scheduling under one containing work ceiling. Restart cadence, optional work
 * and growth triggers share a cooldown that excludes the work the passes themselves spend.
 */
internal class Inprocessing(
    private val passes: List<InprocessingPass>,
    private val cadence: Int,
    private val stats: SearchStatsSink? = null,
) {
    init {
        require(cadence >= 1) { "inprocessing cadence must be >= 1, got $cadence" }
        val eliminating = passes.filter { !it.preservesVariables }
        require(eliminating.isEmpty()) {
            "variable-eliminating passes break the stable-id contract shared clauses rely on: $eliminating"
        }
    }

    private var restartsUntilRun = cadence
    private var nextPass = 0
    private var spent = 0L
    private var debt = 0L
    private var lastWork = 0L
    private var lastCount = 0
    private var pendingRoot: InprocessingPass? = null

    fun onRestart(session: PropagationSession, params: BacktrackParams): Boolean = atRoot(session, params, true)

    fun onRoot(session: PropagationSession, params: BacktrackParams): Boolean = atRoot(session, params, false)

    private fun atRoot(session: PropagationSession, params: BacktrackParams, restart: Boolean): Boolean {
        val beforeResume = session.work
        if (pendingRoot != null) {
            val limit = params.inprocessingWork - minOf(debt, params.inprocessingWork)
            debt = (debt - params.inprocessingWork).coerceAtLeast(0L)
            val token = Cancellation { params.cancellation() || session.work - beforeResume >= limit }
            session.withCancellation(token) {
                pendingRoot?.spending?.run(session, params, stats, resumingRoot = true) { session.resumeInprocessingRoot() }
            }
            val used = (session.work - beforeResume).coerceAtLeast(0L)
            spent = saturatedWork(spent, used)
            debt = saturatedWork(debt, (used - limit).coerceAtLeast(0L))
            stats?.noteInprocessing("inprocessContinuationWork", used)
            if (!session.fixpointCancelled) pendingRoot = null
            return true
        }
        if (!session.canSimplifyRoot || passes.isEmpty()) return false
        val restartDue = restart && --restartsUntilRun <= 0
        if (restartDue) restartsUntilRun = cadence
        val work = (session.work - spent).coerceAtLeast(0L)
        val elapsed = (work - lastWork).coerceAtLeast(0L)
        val intervalDue = params.inprocessingInterval > 0L && elapsed >= params.inprocessingInterval
        val growthDue = params.inprocessingGrowth > 0 &&
            session.learnedClauseCount.toLong() - lastCount >= params.inprocessingGrowth
        if ((!restartDue && !intervalDue && !growthDue) || elapsed < params.inprocessingCooldown) return false
        lastWork = work
        lastCount = session.learnedClauseCount
        val repaid = minOf(debt, params.inprocessingWork)
        debt -= repaid
        val offered = params.inprocessingWork - repaid
        if (offered == 0L || params.cancellation() || session.cancelRequested) return false
        stats?.noteInprocessing(if (restartDue) "inprocessRestartTriggers" else "inprocessWorkTriggers", 1L)
        val start = session.work
        val token = Cancellation { params.cancellation() || session.work - start >= offered }
        try {
            session.withCancellation(token) {
                repeat(passes.size) {
                    if (session.cancelRequested || session.fixpointCancelled || session.isUnsatAtRoot) return@withCancellation
                    val pass = passes[nextPass]
                    nextPass = (nextPass + 1) % passes.size
                    pass.run(session, params, stats)
                    if (session.fixpointCancelled) pendingRoot = pass
                }
            }
        } finally {
            val used = (session.work - start).coerceAtLeast(0L)
            spent = saturatedWork(spent, used)
            debt = saturatedWork(debt, (used - offered).coerceAtLeast(0L))
        }
        return true
    }

    fun reset() {
        restartsUntilRun = cadence
        nextPass = 0
        spent = 0L
        debt = 0L
        lastWork = 0L
        lastCount = 0
        pendingRoot = null
        for (pass in passes) pass.reset()
    }

    companion object {
        /**
         * Assemble the loop an engine's [params] ask for, or null when no pass is enabled. Seeded
         * searches get no loop at all: a pass derives clauses by propagating at the current root,
         * and with assumption pins standing those derivations would hold only under the pins yet be
         * stored as unconditional learned clauses.
         */
        fun from(params: BacktrackParams, stats: SearchStatsSink? = null): Inprocessing? {
            if (!params.assumptions.isEmpty || !params.assumptions.deductions.isEmpty) return null
            val passes = buildList {
                // Subsumption first: it only shrinks the database, so vivification's probing works
                // the surviving clauses instead of ones about to be dropped.
                if (params.rootSimplification) add(RootSimplificationPass())
                if (params.rootProbeBatch > 0) add(RootProbingPass())
                if (params.subsumption) add(SubsumptionPass())
                if (params.vivification) add(VivificationPass())
            }
            if (passes.isEmpty()) return null
            return Inprocessing(passes, params.inprocessingCadence, stats)
        }
    }
}
