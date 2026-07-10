package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.lp.LpEngine
import com.eignex.klause.backtrack.lp.harvestRootCuts
import com.eignex.klause.backtrack.lp.lbTreeSearch
import com.eignex.klause.backtrack.lp.lpBranchPick
import com.eignex.klause.backtrack.lp.lpFeasibilityPump
import com.eignex.klause.backtrack.lp.lpRoundingProbe
import com.eignex.klause.backtrack.lp.rootLpRelaxationBound
import com.eignex.klause.backtrack.lp.shaveObjectiveLb
import com.eignex.klause.backtrack.lp.shaveVariableBounds
import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.Learned
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsatCore
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** One step of [ResumableMinimize.runUntilEvent]: a new incumbent, the terminal verdict, or a
 *  slice-boundary pause (only in pausable mode). */
internal sealed interface StepEvent {
    data class Incumbent(val result: MinimizeResult.WithSample) : StepEvent
    data class Terminal(val result: MinimizeResult) : StepEvent
    data object Paused : StepEvent
}

/**
 * The optimize driver: branch-and-bound minimisation over the shared [DfsEngine]. A thin wrapper that
 * owns the incumbent, the LP-relaxation family (built once; persists across slices = rolling warm
 * starts), the objective-bound propagation, and the stats sink lifecycle; the DFS orchestration itself
 * lives in [DfsEngine], driven through the inner [MinimizePolicy]. [runUntilEvent] advances to the next
 * reportable event (a new incumbent, the terminal verdict, or — in pausable mode — a slice-boundary
 * pause) and returns; the engine retains all state so the next call resumes.
 *
 * Two callers drive it, differing only in [pausable]:
 *  - [BacktrackSolver.improvements] streams it lazily — one [StepEvent.Incumbent] yielded per call, then the terminal;
 *    a fired cancellation is a hard stop.
 *  - [runSlice] runs it one time slice at a time for [com.eignex.klause.portfolio.SequentialPortfolio]
 *    (#381): a fired slice deadline pauses ([StepEvent.Paused]); a later call resumes mid-tree so the
 *    arm never cold-restarts.
 */
internal class ResumableMinimize(
    // Carries the [BacktrackSolver.problem] plus the search primitives defined as its extension
    // functions (advance, applyPhase, backjumpAndLearn, …); the solver holds no state beyond the problem.
    private val solver: BacktrackSolver,
    private val objective: LinearObjective,
    params0: BacktrackParams,
    // true: a fired cancellation is a slice boundary the caller may resume past ([runSlice]); false:
    // it is a hard terminal stop ([BacktrackSolver.improvements] one-shot). The single difference between the two
    // ways this one engine is driven.
    private val pausable: Boolean = true,
) : ResumableSearch {
    private val problem: Problem = solver.problem

    // #429: the LP-relaxation family is resolved from [BacktrackParams.lpConfig] inside [LpEngine]
    // (the single intent→plan home), so this carries the caller's params verbatim — only the slice
    // cancellation and Hamming knobs are fixed here; the per-slice deadline is re-armed in runSlice.
    // The caller's own token (drives the non-pausable one-shot path); superseded by the slice in
    // pausable mode.
    private val baseCancellation: Cancellation = params0.cancellation
    private val params: BacktrackParams = params0
        .copy(cancellation = Cancellation { sliceCancelled() }, minHammingDistance = 0, recentWindow = 0)

    // --- Slice control (no coroutine): a re-armable deadline + the current global token. ---
    private var globalToken: Cancellation = Cancellation.Never
    private var sliceEnd: TimeSource.Monotonic.ValueTimeMark? = null

    // Wall-clock anchor for sizing the LP sub-budgets against [BacktrackParams.solveBudgetMillis] on the
    // non-pausable one-shot path, where no slice deadline is armed. Captured at construction,
    // which is the solve start for that path.
    private val startMark = TimeSource.Monotonic.markNow()
    private fun sliceCancelled(): Boolean = if (pausable) {
        globalToken() || (sliceEnd?.hasPassedNow() ?: false)
    } else {
        baseCancellation()
    }

    // --- Incumbent + objective-bound propagation state. ---
    private var best: Sample? = null
    private var bestObj: Double = Double.POSITIVE_INFINITY
    private val singleObj = objective.singleIntObjective()
    private var objVarBest: Long? = null
    private val externalShared = params.objectiveBoundSupplier != null
    private val sink = SolveStatsSink(backend = "backtrack")
    private var lastObjBoundAsserted: Long? = null

    // --- LP-relaxation family state (built once; persists across slices = rolling warm starts).
    // Always constructed so the always-on linear lower bound runs; its internal bounds are null/empty
    // when their feature flag is off. ---
    private val lpEngine = LpEngine(problem, objective, params, sink)

    private val pruneIf: (PropagationSession) -> Boolean = { session ->
        val externalBound = params.objectiveBoundSupplier?.invoke() ?: Double.POSITIVE_INFINITY
        val effectiveBound = if (externalBound < bestObj) externalBound else bestObj
        lpEngine.pruneNode(
            session,
            effectiveBound,
            singleObj?.varId ?: -1,
            singleObj?.ascending ?: true,
        )
    }
    private val pruneLearned: () -> Learned? = { lpEngine.lastBackjump() }

    // The shared DFS engine holds the live session, trail, phase saving, restart controller and budget;
    // this class supplies the optimize behaviour through [MinimizePolicy].
    private val engine = DfsEngine(solver, params, sink, MinimizePolicy())
    private val session: PropagationSession get() = engine.propagationSession

    // Built lazily so it binds the engine's session (created inside the engine's constructor). Its first
    // use is the first-run boundExchange work, well after construction.
    private val boundExchange by lazy { PortfolioBoundExchange(problem, session, params, singleObj) }

    /** Terminal verdict once the search completes; null while still pending. */
    private var done: MinimizeResult? = null
    override val isDone: Boolean get() = done != null

    override fun runSlice(
        global: Cancellation,
        sliceMillis: Long,
        onIncumbent: (MinimizeResult.WithSample) -> Unit,
    ): MinimizeResult? {
        done?.let { return it }
        globalToken = global
        sliceEnd = TimeSource.Monotonic.markNow() + sliceMillis.milliseconds
        while (true) {
            when (val e = runUntilEvent()) {
                is StepEvent.Incumbent -> onIncumbent(e.result)
                is StepEvent.Terminal -> return e.result
                StepEvent.Paused -> return null
            }
        }
    }

    override fun close() {
        runCatching { sink.stop() }
    }

    /** Advance the search to the next reportable event, mapping the engine's [EngineEvent] to a
     *  [StepEvent]. Visible to the enclosing solver (which streams it from [BacktrackSolver.improvements]). */
    fun runUntilEvent(): StepEvent {
        done?.let { return StepEvent.Terminal(it) }
        return when (val e = engine.runUntilEvent()) {
            is EngineEvent.Solution -> StepEvent.Incumbent(e.payload)
            is EngineEvent.Exhausted -> terminal(terminalExhausted(e.core))
            EngineEvent.BudgetCapped -> terminal(terminalBudget())
            EngineEvent.Cancelled -> if (pausable) StepEvent.Paused else terminal(terminalBudget())
        }
    }

    private fun terminal(result: MinimizeResult): StepEvent {
        done = result
        return StepEvent.Terminal(result)
    }

    private fun terminalExhausted(core: UnsatCore?): MinimizeResult {
        sink.stop()
        val stats = sink.snapshot()
        val b = best
        return when {
            externalShared && b != null ->
                MinimizeResult.BestFound(b, bestObj, TerminationReason.SearchExhausted, stats)

            externalShared -> MinimizeResult.Unknown(TerminationReason.SearchExhausted, stats)

            b != null -> MinimizeResult.Optimal(b, bestObj, stats)

            else -> MinimizeResult.Infeasible(core, stats)
        }
    }

    private fun terminalBudget(): MinimizeResult {
        sink.stop()
        sink.timedOut = true
        val stats = sink.snapshot()
        val b = best
        return if (b != null) {
            MinimizeResult.BestFound(b, bestObj, TerminationReason.BudgetExhausted, stats)
        } else {
            MinimizeResult.Unknown(TerminationReason.BudgetExhausted, stats)
        }
    }

    /** Assert the incumbent bound on the objective var at the root; true iff that empties the root
     *  (optimum proven). */
    private fun assertObjectiveBoundAtRoot(): Boolean {
        val objectiveVar = singleObj?.varId ?: return false
        val b = objVarBest ?: return false
        val ascending = singleObj.ascending
        val threshold = if (ascending) b - 1 else b + 1
        if (threshold == lastObjBoundAsserted) return false
        lastObjBoundAsserted = threshold
        return session.assertObjectiveBound(objectiveVar, threshold, atMost = ascending) is PropagationResult.Unsat
    }

    /** Fold [sample] (objective [o]) into the incumbent when it strictly improves the best; fires
     *  inline telemetry and returns the incumbent to surface, or null when it isn't an improvement. */
    private fun recordIfImproving(sample: Sample, o: Double): MinimizeResult.WithSample? {
        if (o >= bestObj) return null
        bestObj = o
        best = sample
        if (singleObj != null) objVarBest = sample.ints[singleObj.varId]
        params.onEvent?.invoke(SearchEvent.Incumbent(o))
        return MinimizeResult.BestFound(sample, o, TerminationReason.BudgetExhausted)
    }

    /**
     * One-shot pre-search root work (harvest the global cut pool, capture the root relaxation bound for
     * the integrality-gap metric, root shaving, bound exchange, and the primal probes). Run on the first
     * engine step so the live cancellation gates the LP solves. Returns the first incumbent if a probe
     * seeds one (in which case the tree-search probe is skipped this call, matching the pre-unification
     * single first-run block), else null. The root-LP work is time-boxed by [rootLpBudget] (#31).
     */
    private fun firstRunWork(): MinimizeResult.WithSample? {
        sink.start()
        // Charge the one-shot root LP work's wall time against the shared LP wall budget on every
        // exit path, so it competes with the per-node solves for the same fraction of the deadline.
        val rootWorkStart = TimeSource.Monotonic.markNow()
        try {
            return firstRunWorkBody()
        } finally {
            lpEngine.chargeRootLpWall(rootWorkStart.elapsedNow().inWholeMilliseconds)
        }
    }

    private fun firstRunWorkBody(): MinimizeResult.WithSample? {
        val rootToken = rootLpBudget()
        initRootLp(rootToken)
        // Objective shaving: raise the objective's proven lower bound before search when the LP +
        // propagation prove lower values infeasible. Sound — every raise is a proof.
        if (lpEngine.params.lpPlan.objectiveShaving) {
            singleObj?.let { obj ->
                lpEngine.shaveObjectiveLb(obj.varId, obj.ascending, rootToken)?.let { lb ->
                    session.implyIntAtLeast(obj.varId, lb)
                }
            }
        }
        // Variable shaving: tighten integer domains the LP + propagation prove cannot reach their
        // declared bounds. Sound — each tightening is a proof.
        if (lpEngine.params.lpPlan.variableShaving) {
            for (sb in lpEngine.shaveVariableBounds(rootToken)) {
                session.implyIntAtLeast(sb.varId, sb.lo)
                session.implyIntAtMost(sb.varId, sb.hi)
            }
        }
        boundExchange.applySharedFloor()
        // Publish the root floor (post-shaving) so peers see the bound this arm proved up front.
        boundExchange.publishFloor()
        // Exchange globally-valid level-0 variable tightenings: import peers' first, then publish this
        // arm's (root propagation + shaving), before any incumbent-relative fixing runs.
        boundExchange.importGlobalVarBounds()
        boundExchange.publishGlobalVarBounds()
        // LP-rounding primal heuristic (#287): seed an incumbent before search so the bound prunes and
        // reduced-cost fixing bite from the first node.
        if (lpEngine.params.lpPlan.probe && lpEngine.lpRelaxer != null) {
            // Single-shot rounding first; if it can't land a feasible point, pump toward one.
            val seed = lpEngine.lpRoundingProbe(objective, rootToken)
                ?: lpEngine.lpFeasibilityPump(objective, rootToken)
            if (seed != null) recordIfImproving(seed, objective.evaluate(seed))?.let { return it }
        }
        // Best-bound tree-search primal subsolver: dive best-first for an incumbent. Pure heuristic —
        // the returned assignment is propagation-feasible and re-evaluated here.
        if (lpEngine.params.lpPlan.lbTreeSearch && lpEngine.lpRelaxer != null) {
            lpEngine.lbTreeSearch(objective, rootToken)?.let { seed ->
                recordIfImproving(seed, objective.evaluate(seed))?.let { return it }
            }
        }
        return null
    }

    /**
     * One-shot pre-search LP work: harvest the global cut pool and capture the root relaxation bound for
     * the integrality-gap metric (search only bounds from level 1 down, so this is the sole root
     * capture). [token] is the shared root-LP budget (#31) — the slice/global cancellation time-boxed to
     * `LpPlan.rootBudgetFraction`.
     */
    private fun initRootLp(token: Cancellation) {
        // Drop hulls that add no root strength before the harvest + persistent base read the relaxer.
        if (lpEngine.params.lpPlan.pruneHulls) lpEngine.pruneIneffectiveHulls(token)
        val relaxer = lpEngine.lpRelaxer ?: return
        val gomory = lpEngine.params.lpPlan.cuts && lpEngine.params.lpPlan.gomory
        val mir = lpEngine.params.lpPlan.cuts && lpEngine.params.lpPlan.mir
        if (lpEngine.lpSeparators.isNotEmpty() || gomory || mir) {
            lpEngine.cutPool.addAll(
                lpEngine.harvestRootCuts(
                    relaxer,
                    PropagationSession(problem),
                    lpEngine.lpSeparators,
                    gomory,
                    mir,
                    token,
                ),
            )
            sink.lp.observeCuts(lpEngine.lpGlobalCuts.size)
        }
        val rootBound = lpEngine.rootLpRelaxationBound(relaxer, lpEngine.lpGlobalCuts, token)
        sink.lp.observeRootBound(0, rootBound)
        // Publish the root LP bound to the portfolio's shared lower-bound manager: a sound global lower
        // bound on the optimum, so a peer arm can pair it with its own incumbent to prove optimality. A
        // NaN (no LP structure) carries no information and the sink ignores it.
        if (!rootBound.isNaN()) params.objectiveLowerBoundSink?.invoke(rootBound)
    }

    /**
     * The shared cooperative-cancellation budget for the pre-search root LP work (#31): the
     * slice/global [BacktrackParams.cancellation] OR-ed with a wall-clock deadline of
     * `LpPlan.rootBudgetFraction` of the time remaining (capped at `LpPlan.rootBudgetMillis`). The time
     * remaining is read from the current slice deadline when one is armed, else from
     * [BacktrackParams.solveBudgetMillis] and [startMark] on the non-pausable one-shot path — so
     * the cap tracks the real deadline on the FD track too instead of degrading to the absolute ceiling,
     * which exceeds a short budget and let root work consume the whole solve. Only the absolute cap
     * applies when neither the slice end nor the budget is known. A non-positive fraction disables the
     * cap — the prior behaviour.
     */
    private fun rootLpBudget(): Cancellation {
        val fraction = params.lpPlan.rootBudgetFraction
        if (fraction <= 0.0) return params.cancellation
        val cap = params.lpPlan.rootBudgetMillis
        val remaining = remainingBudgetMillis()
        var budgetMillis = if (remaining != null) minOf((remaining * fraction).toLong(), cap) else cap
        // Also cap the one-shot root work by the shared LP wall budget, so an expensive-but-useless
        // root relaxation cannot spend more than the whole LP subsystem is allotted before search starts.
        lpEngine.lpWallRemainingMillis()?.let { budgetMillis = minOf(budgetMillis, it) }
        return params.cancellation or Cancellation.after(budgetMillis.coerceAtLeast(0).milliseconds)
    }

    /** Milliseconds left until the effective deadline: the armed slice end (pausable portfolio path),
     *  else [BacktrackParams.solveBudgetMillis] minus the elapsed since [startMark] (the one-shot path),
     *  else null when no budget is known. */
    private fun remainingBudgetMillis(): Long? {
        sliceEnd?.let { return (it - TimeSource.Monotonic.markNow()).inWholeMilliseconds }
        return params.solveBudgetMillis?.let { it - startMark.elapsedNow().inWholeMilliseconds }
    }

    /** Drains the LP-learned Farkas nogoods at a restart, registering them permanently and sharing each
     *  globally (its LBD = length would never clear the glue export filter, #844). Returns true iff a
     *  root contradiction proves the whole space empty. */
    private fun drainLpNogoods(): Boolean {
        val lpNogoods = lpEngine.lpNogoods ?: return false
        for (nogood in lpNogoods.drain()) {
            val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
            if (res is PropagationResult.Unsat) return true
            // An LP Farkas nogood is globally valid (the relaxation is infeasible regardless of the
            // incumbent), so share it with peer arms directly.
            params.clauseExchange?.publishGlobal(session.asSharedClause(nogood, nogood.size))
        }
        return false
    }

    /** The optimize behaviour plugged into [DfsEngine]: LP node pruning + branch hints, the incumbent
     *  fold at each leaf, the objective-bound assertion, and the cut/bound exchanges at each restart. */
    private inner class MinimizePolicy : SearchPolicy<MinimizeResult.WithSample> {
        override val pruneIf: (PropagationSession) -> Boolean get() = this@ResumableMinimize.pruneIf
        override val pruneLearned: () -> Learned? get() = this@ResumableMinimize.pruneLearned

        override fun cancelled(): Boolean = sliceCancelled()

        // Reduced-cost-average branching: prefer the LP's most cost-impactful fractional variable; null
        // falls back to the configured selector.
        override fun branchPick(session: PropagationSession): VarRef? = lpEngine.lpBranchPick(session)

        override fun orderValues(varRef: VarRef, values: Sequence<Long>): Sequence<Long> =
            lpEngine.lpHints?.order(varRef, values) ?: values

        // Always block this leaf and backtrack (the engine does that); surface it only when it strictly
        // improves the incumbent.
        override fun onLeaf(snap: Sample): MinimizeResult.WithSample? =
            recordIfImproving(snap, objective.evaluate(snap))

        override fun assertObjectiveBoundAtRoot(session: PropagationSession): Boolean =
            this@ResumableMinimize.assertObjectiveBoundAtRoot()

        override fun drainLpNogoodsAtRestart(session: PropagationSession): Boolean = drainLpNogoods()

        override fun onRestartCuts(session: PropagationSession) {
            params.cutExchange?.let { lpEngine.exchangeCuts(it) }
        }

        override fun onRestartBounds(session: PropagationSession) {
            // At level 0 import the shared objective lower bound (tightening this arm's objVar) and
            // republish this arm's own raised floor, so a bound proven mid-search propagates through the
            // pool. Import peers' globally-valid level-0 variable tightenings (import only — not publish,
            // since level-0 domains here may carry this arm's incumbent-relative fixings, not global).
            boundExchange.applySharedFloor()
            boundExchange.publishFloor()
            boundExchange.importGlobalVarBounds()
        }

        override fun onFirstRun(): MinimizeResult.WithSample? = firstRunWork()

        override fun onResumeEntry() {
            // Re-read the shared lower bound each slice: a peer arm may have proven a tighter floor.
            // Monotone and sound, so re-asserting only strengthens pruning.
            boundExchange.applySharedFloor()
        }
    }
}
