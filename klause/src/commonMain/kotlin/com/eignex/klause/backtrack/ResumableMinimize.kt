package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.lp.LpHints
import com.eignex.klause.backtrack.lp.lbTreeSearch
import com.eignex.klause.backtrack.lp.lpBranchPick
import com.eignex.klause.backtrack.lp.lpFeasibilityPump
import com.eignex.klause.backtrack.lp.lpRoundingProbe
import com.eignex.klause.backtrack.selector.IndomainBest
import com.eignex.klause.backtrack.selector.SolutionGuided
import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.lp.bounding.LpEngine
import com.eignex.klause.lp.bounding.harvestRootCuts
import com.eignex.klause.lp.bounding.rootLpRelaxationBound
import com.eignex.klause.lp.bounding.shaveObjectiveLb
import com.eignex.klause.lp.bounding.shaveVariableBounds
import com.eignex.klause.lp.engine.LpVerdict
import com.eignex.klause.lp.relaxation.leafRealFeasibility
import com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.LearnedConstraint
import com.eignex.klause.propagation.CpBranching
import com.eignex.klause.propagation.CpSearchComponent
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStats
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.solver.search.BooleanBranching
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchComponentSet
import com.eignex.klause.solver.search.SearchContext
import com.eignex.klause.solver.search.SearchDecisionBudget
import com.eignex.klause.solver.search.SearchLearnedConflict
import com.eignex.klause.solver.search.SearchLearnedConflictResult
import com.eignex.klause.solver.search.SearchModelContinuation
import com.eignex.klause.solver.search.SearchModelDisposition
import com.eignex.klause.solver.search.SearchModelPolicy
import com.eignex.klause.solver.search.SearchNodeDisposition
import com.eignex.klause.solver.search.SearchNodePolicy
import com.eignex.klause.solver.search.SearchRun
import com.eignex.klause.solver.search.SearchRunDisposition
import com.eignex.klause.solver.search.SearchRunEvent
import com.eignex.klause.solver.search.SearchRunLifecycle
import com.eignex.klause.solver.search.SearchSolveParams
import com.eignex.klause.solver.search.SearchTraversalPolicy
import com.eignex.klause.util.Cancellation
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
 * The optimize driver: branch-and-bound minimisation over the shared [SearchRun]. A thin wrapper that
 * owns the incumbent, the LP-relaxation family (built once; persists across slices = rolling warm
 * starts), the objective-bound propagation, and the stats sink lifecycle; the DFS orchestration itself
 * lives in [SearchRun], driven through typed model, node, and lifecycle policies. [runUntilEvent] advances to the next
 * reportable event (a new incumbent, the terminal verdict, or — in pausable mode — a slice-boundary
 * pause) and returns; the engine retains all state so the next call resumes.
 *
 * Two callers drive it, differing only in [pausable]:
 *  - [BacktrackSolver.improvements] streams it lazily — one [StepEvent.Incumbent] yielded per call, then the terminal;
 *    a fired cancellation is a hard stop.
 *  - [runSlice] runs it one time slice at a time for [com.eignex.klause.portfolio.SequentialPortfolio]:
 *    a fired slice deadline pauses ([StepEvent.Paused]); a later call resumes mid-tree so the
 *    arm never cold-restarts.
 */
internal class ResumableMinimize(
    // Carries the immutable problem and shared search helpers; the solver holds no per-run state.
    private val solver: BacktrackSolver,
    private val objective: LinearObjective,
    params0: BacktrackParams,
    // true: a fired cancellation is a slice boundary the caller may resume past ([runSlice]); false:
    // it is a hard terminal stop ([BacktrackSolver.improvements] one-shot). The single difference between the two
    // ways this one engine is driven.
    private val pausable: Boolean = true,
) : ResumableSearch {
    private val problem: Problem = solver.problem

    /** Set when a leaf's residual continuous LP was neither certified feasible nor infeasible, so an
     *  exhausted search with no incumbent must report `unknown` rather than Infeasible. */
    private var sawIndeterminateLeaf = false

    // The LP-relaxation family is resolved from [BacktrackParams.lpConfig] inside [LpEngine]
    // (the single intent→plan home), so this carries the caller's params verbatim — only the slice
    // cancellation and Hamming knobs are fixed here; the per-slice deadline is re-armed in runSlice.
    // The caller's own token (drives the non-pausable one-shot path); superseded by the slice in
    // pausable mode.
    private val baseCancellation: Cancellation = params0.cancellation
    private val params: BacktrackParams = params0
        .copy(cancellation = Cancellation { sliceCancelled() }, minHammingDistance = 0, recentWindow = 0)
        .let { p ->
            // Objective-guided value selection: dive toward the cost-minimising polarity first. Applied on
            // the minimisation path when the objective is non-trivial, kept incumbent-guided on top; a no-op
            // for a pure-satisfaction objective (all coefficients zero → IndomainBest is IndomainMin).
            if (p.objectiveGuidedValues && objectiveHasWeight()) {
                p.copy(valueSelector = SolutionGuided(IndomainBest(objective)))
            } else {
                p
            }
        }

    private fun objectiveHasWeight(): Boolean =
        objective.boolWeights.any { it != 0L } || objective.intCoefficients.any { it != 0L }

    // Slice control without a coroutine: a re-armable deadline plus the current global token.
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

    private var best: Sample? = null
    private var bestObj: Double = Double.POSITIVE_INFINITY
    private val singleObj = objective.singleIntObjective()
    private var objVarBest: Long? = null
    private val externalShared = params.objectiveBoundSupplier != null
    private val sink = SolveStatsSink(backend = "backtrack")
    private var lastObjBoundAsserted: Long? = null
    private var lastBoolCutoffRhs: Long? = null
    private var lastOpenCutoff: Long? = null

    // Built once; persists across slices, so warm starts roll forward. Always constructed so the
    // always-on linear lower bound runs; its internal bounds are null/empty when their feature flag
    // is off.
    private val lpEngine = LpEngine(problem, objective, params.lpParams(), sink)

    // LP-guided branching hints (search-only): owned here so the engine depends only on the record sink.
    // The engine records each node's LP solution into it; the descent reads it for variable/value order.
    private val lpHints: LpHints? =
        if (params.lpPlan.branching) LpHints(problem.numIntVars, problem.numBoolVars) else null

    init {
        lpEngine.lpHints = lpHints
    }

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
    private val cp = CpSearchComponent(
        PropagationSession(
            problem,
            params.cancellation,
            params.propagationCancelFloor,
            nativeSat = params.nativeSat ?: true,
            pbLearning = params.pbLearning ?: true,
        ),
        branching = CpBranching.None,
    )
    private val session: PropagationSession get() = cp.session
    private val restart = RestartSchedule.from(params)
    private var decisionLimit = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE)
    private val brancher = BacktrackBrancher(session, params, sink, restart, LpGuidedBranching())
    private val searchSession = SearchComponentSet(listOf(cp), branchers = listOf(brancher)).session(
        cancellation = params.cancellation,
        learnedDb = params.sharedLearnedDb(),
    )
    private val run: SearchRun
    private var rootExhausted: UnsatCore? = null
    private var rootIsExhausted = false
    private var firstRun = true
    private val inprocessing = Inprocessing.from(params)
    private var lastPooledSolution: Sample? = null
    private val traversal = OptimizationTraversalPolicy()

    // Built lazily so it binds the engine's session (created inside the engine's constructor). Its first
    // use is the first-run boundExchange work, well after construction.
    private val boundExchange by lazy { PortfolioBoundExchange(problem, session, params, singleObj) }

    /** Terminal verdict once the search completes; null while still pending. */
    private var done: MinimizeResult? = null
    private var pendingIncumbent: MinimizeResult.WithSample? = null
    override val isDone: Boolean get() = done != null

    /** Live counters; [SolveStatsSink.snapshot] reads elapsed time without needing the sink stopped, so
     *  this is meaningful mid-search as well as after one. */
    override val stats: SolveStats get() = sink.snapshot()

    init {
        val seeded = session.seed(params.assumptions)
        cp.rebase()
        if (seeded is PropagationResult.Unsat || session.isUnsatAtRoot) {
            rootExhausted = (problem.baked as? PropagationResult.Unsat)?.let(::coreOf)
            rootIsExhausted = true
        } else {
            when (searchSession.initialize()) {
                ComponentResult.Consistent -> params.clauseExchange?.onSearchStart(session)
                is ComponentResult.Conflict -> rootExhausted = null
                ComponentResult.Indeterminate -> Unit
            }
        }
        run = searchSession.openRun(problem.numBoolVars, traversal)
    }

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

    /**
     * Re-drive this search on a new [assumptions] set with a fresh [decisionBudget] (LNS repair):
     * Re-seeds the persistent session and resets the fragment incumbent, while the LP
     * relaxation warm start and the session's learned-clause database survive. The caller must drive
     * successive repairs against a monotone non-increasing objective cutoff.
     */
    fun rebind(assumptions: Assumptions, decisionBudget: Long) {
        searchSession.popTo(0)
        val seeded = session.reseedFrom(assumptions)
        cp.rebase()
        searchSession.resetRootFacts()
        rootIsExhausted = seeded is PropagationResult.Unsat || session.isUnsatAtRoot
        rootExhausted = null
        if (!rootIsExhausted) {
            when (searchSession.initialize()) {
                ComponentResult.Consistent -> params.clauseExchange?.onSearchStart(session)
                is ComponentResult.Conflict -> rootExhausted = null
                ComponentResult.Indeterminate -> Unit
            }
        }
        decisionLimit = decisionBudget
        run.reset()
        inprocessing?.reset()
        firstRun = true
        best = null
        bestObj = Double.POSITIVE_INFINITY
        objVarBest = null
        lastObjBoundAsserted = null
        lastBoolCutoffRhs = null
        lastOpenCutoff = null
        done = null
    }

    /** Advance the search to the next reportable [StepEvent].
     *
     * Visible to the enclosing solver, which streams it from [BacktrackSolver.improvements].
     */
    fun runUntilEvent(): StepEvent {
        done?.let { return StepEvent.Terminal(it) }
        if (rootIsExhausted) return terminal(terminalExhausted(rootExhausted))
        if (firstRun) {
            firstRun = false
            firstRunWork()?.let { return StepEvent.Incumbent(it) }
        }
        return when (val e = run.next()) {
            is SearchRunEvent.Satisfied -> StepEvent.Incumbent(checkNotNull(pendingIncumbent))

            SearchRunEvent.Exhausted -> terminal(terminalExhausted(null))

            SearchRunEvent.Paused -> StepEvent.Paused

            is SearchRunEvent.Indeterminate -> if (pausable && sliceCancelled()) {
                StepEvent.Paused
            } else {
                terminal(terminalBudget())
            }
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

            // No incumbent, but a leaf's continuous LP was uncertifiable — the tree is not provably
            // all-infeasible, so report `unknown` rather than an unsound Infeasible.
            sawIndeterminateLeaf -> MinimizeResult.Unknown(TerminationReason.Unsupported, stats)

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
        val objectiveVar = singleObj?.varId ?: return postBoolObjectiveCutoffAtRoot()
        val b = objVarBest ?: return false
        val ascending = singleObj.ascending
        val threshold = if (ascending) b - 1 else b + 1
        if (threshold == lastObjBoundAsserted) return false
        lastObjBoundAsserted = threshold
        return session.assertObjectiveBound(objectiveVar, threshold, atMost = ascending) is PropagationResult.Unsat
    }

    /**
     * Close the integer columns the front-end could only bound by inventing a box, now that an incumbent
     * prices them: see [objectiveCutoffBounds]. Fired at the root alongside the objective bound, so the
     * tightening is level-0 permanent and the branching that follows sees a real range instead of the
     * box; re-run only once the incumbent improves. Returns true iff it empties the root — nothing beats
     * the incumbent, so the optimum is proven.
     *
     * The bound cuts everything that is not *better* than the incumbent, which is sound for proving an
     * optimum and wrong for enumeration. This driver is the branch-and-bound optimize path, which only
     * ever surfaces strictly-improving solutions ([recordIfImproving]); an all-solutions run enumerates
     * through a different search entirely and never reaches here.
     */
    private fun tightenOpenColumnsAtRoot(): Boolean {
        if (!bestObj.isFinite()) return false
        val value = bestObj.toLong()
        if (value.toDouble() != bestObj) return false // non-integral incumbent: no exact integer cutoff
        val last = lastOpenCutoff
        if (last != null && value >= last) return false
        lastOpenCutoff = value
        for (b in objectiveCutoffBounds(problem, objective, value)) {
            if (session.implyIntAtMost(b.varId, b.hi) is PropagationResult.Unsat) return true
        }
        return false
    }

    /**
     * For a pure-Boolean weighted objective (no single objective variable) post the incumbent cutoff
     * `Σ boolWeights_b·x_b ≤ bestObj − constant − 1` as a permanent pseudo-Boolean constraint, so CDCL and
     * pseudo-Boolean cutting-planes learning can *refute* it — the mechanism that proves optimality (a
     * weighted-Boolean objective has no objective variable to bound, so tree exhaustion alone rarely
     * closes it). Fired at the root on each restart, re-posting only when the incumbent improved. The
     * cutoff normalizes to `Σ |w_b|·ℓ_b ≥ degree` with `ℓ_b = x_b` when `w_b < 0` else `¬x_b`,
     * `degree = Σ_{w_b>0} w_b − (bestObj − constant − 1)`. Returns true iff it empties the root
     * (optimum proven). Integer/mixed objectives keep the objective-variable path above.
     */
    private fun postBoolObjectiveCutoffAtRoot(): Boolean {
        if (!params.pbObjectiveCutoff || problem.numIntVars > 0 || !bestObj.isFinite()) return false
        val bestLong = bestObj.toLong()
        if (bestLong.toDouble() != bestObj) return false // non-integral incumbent: no exact integer cutoff
        val cutoffRhs = bestLong - objective.constant - 1 // Σ w_b·x_b must be ≤ this to beat the incumbent
        if (cutoffRhs == lastBoolCutoffRhs) return false
        lastBoolCutoffRhs = cutoffRhs
        val weights = objective.boolWeights
        val nb = minOf(problem.numBoolVars, weights.size)
        var posSum = 0L
        var nnz = 0
        for (b in 0 until nb) {
            val w = weights[b]
            if (w == 0L) continue
            nnz++
            if (w > 0L) posSum += w
        }
        if (nnz == 0) return false
        val degree = posSum - cutoffRhs
        if (degree <= 0L) return false // every assignment already beats the cutoff — nothing to enforce
        val pbWeights = LongArray(nnz)
        val pbLits = IntArray(nnz)
        var i = 0
        for (b in 0 until nb) {
            val w = weights[b]
            if (w == 0L) continue
            pbWeights[i] = if (w < 0L) -w else w
            pbLits[i] = Lit.make(b, w < 0L)
            i++
        }
        return session.addLearnedPb(pbWeights, pbLits, degree, lbd = 1, permanent = true) is PropagationResult.Unsat
    }

    /** Fold [sample] (objective [o]) into the incumbent when it strictly improves the best; fires
     *  inline telemetry and returns the incumbent to surface, or null when it isn't an improvement. */
    private fun recordIfImproving(sample: Sample, o: Double): MinimizeResult.WithSample? {
        // A real-variable problem's incumbent must carry the continuous values a leaf's residual LP
        // validated and attached; a sample without them came from a heuristic that neither solved nor
        // certified the reals, so it is neither complete nor sound to surface. Reject it (only the leaf
        // verdict produces a valid real-var incumbent).
        if (problem.numRealVars > 0 && sample.reals.size < problem.numRealVars) return null
        if (o >= bestObj) return null
        bestObj = o
        best = sample
        if (singleObj != null) objVarBest = sample.ints[singleObj.varId]
        params.improvedSolutionSink?.invoke(sample, o)
        params.onEvent?.invoke(SearchEvent.Incumbent(o))
        // Carry the counters as they stand. A caller that consumes the improvement stream and stops when
        // it dries up holds only the last step it saw, so an incumbent without stats leaves the whole run
        // reporting nothing — search and LP alike. Snapshotting mid-search is sound: it reads elapsed time
        // live rather than requiring the sink to have been stopped.
        return MinimizeResult.BestFound(sample, o, TerminationReason.BudgetExhausted, sink.snapshot())
    }

    /**
     * One-shot pre-search root work (harvest the global cut pool, capture the root relaxation bound for
     * the integrality-gap metric, root shaving, bound exchange, and the primal probes). Run on the first
     * engine step so the live cancellation gates the LP solves. Returns the first incumbent if a probe
     * seeds one (in which case the tree-search probe is skipped this call), else null. The root-LP work
     * is time-boxed by [rootLpBudget].
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
        // These primal heuristics seed an integer incumbent from the LP/rounding without solving the
        // residual real LP, so their sample carries no continuous values and is not certified real-
        // feasible. With LP-only continuous variables only the leaf verdict may surface a
        // solution (it validates the reals and attaches them); skip the heuristics there.
        if (problem.numRealVars == 0) {
            // LP-rounding primal heuristic: seed an incumbent before search so the bound prunes and
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
        }
        return null
    }

    /**
     * One-shot pre-search LP work: harvest the global cut pool and capture the root relaxation bound for
     * the integrality-gap metric (search only bounds from level 1 down, so this is the sole root
     * capture). [token] is the shared root-LP budget — the slice/global cancellation time-boxed to
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
     * The shared cooperative-cancellation budget for the pre-search root LP work: the
     * slice/global [BacktrackParams.cancellation] OR-ed with a wall-clock deadline of
     * `LpPlan.rootBudgetFraction` of the time remaining (capped at `LpPlan.rootBudgetMillis`). The time
     * remaining is read from the current slice deadline when one is armed, else from
     * [BacktrackParams.solveBudgetMillis] and [startMark] on the non-pausable one-shot path — so
     * the cap tracks the real deadline on the FD track too instead of degrading to the absolute ceiling,
     * which exceeds a short budget and would let root work consume the whole solve. Only the absolute cap
     * applies when neither the slice end nor the budget is known. A non-positive fraction disables the
     * cap.
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
     *  globally (its LBD = length would never clear the glue export filter). Returns true iff a
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

    /** LP guidance affects only branch order; the shared runner still owns every frame and retraction. */
    private inner class LpGuidedBranching : BacktrackBranching {
        override fun pick(session: PropagationSession): VarRef? = lpEngine.lpBranchPick(session, lpHints)

        override fun orderValues(variable: VarRef, values: Sequence<Long>): Sequence<Long> =
            lpHints?.order(variable, values) ?: values
    }

    /** Refutes LP-dominated partial assignments through the shared frame stack. */
    private inner class LpNodePolicy : SearchNodePolicy {
        override fun beforeBranch(context: SearchContext): SearchNodeDisposition {
            if (!pruneIf(session)) return SearchNodeDisposition.Expand
            val learned = lpEngine.lastBackjump()
            return if (learned != null &&
                learned.asserting &&
                learned.guardLiterals.none { session.litTruth(it) == true }
            ) {
                sink.lp.observeBackjump()
                SearchNodeDisposition.Backjump(LpLearnedConflict(learned))
            } else {
                SearchNodeDisposition.Prune
            }
        }
    }

    /** Adapts an asserting LP Farkas consequence to the shared traversal's native backjump contract. */
    private inner class LpLearnedConflict(private val learned: LearnedConstraint) : SearchLearnedConflict {
        override val decisionLevel: Int get() = cp.sharedLevelForNative(learned.backjumpLevel)
        override val lbd: Int get() = learned.lbd
        override val guardLiterals: IntArray get() = learned.guardLiterals
        override val decisionLevels: IntArray get() = learned.decisionLevels

        override fun apply(session: com.eignex.klause.solver.search.SearchSession): SearchLearnedConflictResult {
            if (!learned.asserting || learned.guardLiterals.isEmpty()) {
                return SearchLearnedConflictResult.Chronological
            }
            val result = when (learned) {
                is com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.Learned -> {
                    this@ResumableMinimize.session.addLearnedClause(Clause(learned.literals), learned.lbd)
                }

                is com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.LearnedPb -> {
                    this@ResumableMinimize.session.addLearnedPb(
                        learned.weights,
                        learned.literals,
                        learned.degree,
                        learned.lbd,
                    )
                }
            }
            return when (result) {
                is PropagationResult.Implied -> {
                    // The clause stays in the CP database; see the note in CpSearchComponent.
                    if (cp.import(result, session) !is ComponentResult.Consistent) {
                        SearchLearnedConflictResult.Chronological
                    } else {
                        when (session.propagate()) {
                            ComponentResult.Consistent -> SearchLearnedConflictResult.Resume
                            is ComponentResult.Conflict -> SearchLearnedConflictResult.Chronological
                            ComponentResult.Indeterminate -> SearchLearnedConflictResult.Indeterminate
                        }
                    }
                }

                is PropagationResult.Unsat -> {
                    val next = result.learnedClause as? LearnedConstraint
                    when {
                        next == null -> SearchLearnedConflictResult.Chronological

                        next.backjumpLevel == 0 && next.guardLiterals.isEmpty() -> {
                            SearchLearnedConflictResult.Exhausted
                        }

                        else -> SearchLearnedConflictResult.Backjump(LpLearnedConflict(next))
                    }
                }
            }
        }
    }

    /** Turns feasible shared models into strictly improving optimization incumbents. */
    private inner class IncumbentPolicy : SearchModelPolicy {
        override fun onModel(
            model: com.eignex.klause.solver.search.AssembledSearchModel,
            context: SearchContext,
        ): SearchModelDisposition {
            val sample = checkNotNull(model.valueOf<Sample>(cp))
            brancher.onSolution(sample)
            val incumbent = if (problem.numRealVars == 0) {
                recordIfImproving(sample, objective.evaluate(sample))
            } else {
                val real = leafRealFeasibility(
                    problem,
                    objective,
                    sample,
                    Cancellation { sliceCancelled() },
                    componentSplit = params.lpPlan.componentSplit,
                    sink = sink.lp,
                )
                when (real.verdict) {
                    LpVerdict.INFEASIBLE -> null

                    LpVerdict.INDETERMINATE -> {
                        sawIndeterminateLeaf = true
                        null
                    }

                    LpVerdict.OPTIMAL -> {
                        val full = sample.copy(reals = real.reals)
                        recordIfImproving(full, objective.evaluate(full))
                    }
                }
            }
            return if (incumbent == null) {
                SearchModelDisposition.Continue
            } else {
                pendingIncumbent = incumbent
                SearchModelDisposition.Surface
            }
        }
    }

    /** CP optimization configuration and root-boundary work for the shared traversal. */
    private inner class OptimizationTraversalPolicy :
        SearchTraversalPolicy,
        SearchRunLifecycle {
        override val solveParams = SearchSolveParams(maxDecisions = Long.MAX_VALUE, restart = restart)
        override val booleanBranching = BooleanBranching.None

        // Two allowances, and a decision is charged against both. [decisionLimit] bounds one slice and is
        // refilled whenever a driver re-enters — a restart, an arm resuming, an LNS repair — so it never
        // bounds a solve. [BacktrackParams.nodeBudget] is the solve-spanning one, and an optimize run that
        // read only the slice allowance ignored `node-limit` entirely: capping `flugpl` at 1000, 5000 and
        // 20000 all visited the same 920695 nodes.
        override val decisionBudget = SearchDecisionBudget {
            val withinSlice = --decisionLimit >= 0L
            val budget = params.nodeBudget ?: return@SearchDecisionBudget withinSlice
            budget.spend()
            withinSlice && !budget.exhausted()
        }
        override val observer = brancher
        override val modelContinuation = SearchModelContinuation.BlockAtRoot
        override val modelPolicy: SearchModelPolicy = IncumbentPolicy()
        override val nodePolicy: SearchNodePolicy = LpNodePolicy()
        override val lifecycle: SearchRunLifecycle get() = this

        override fun onResume(context: SearchContext): SearchRunDisposition {
            boundExchange.applySharedFloor()
            return SearchRunDisposition.Continue
        }

        override fun onModelBlocked(context: SearchContext): SearchRunDisposition = applyObjectiveBound()

        override fun onRestart(context: SearchContext): SearchRunDisposition {
            if (drainLpNogoods()) return SearchRunDisposition.Exhausted
            params.clauseExchange?.onRestart(session)
            params.cutExchange?.let { lpEngine.exchangeCuts(it) }
            val bound = applyObjectiveBound()
            if (bound != SearchRunDisposition.Continue) return bound
            boundExchange.applySharedFloor()
            boundExchange.publishFloor()
            boundExchange.importGlobalVarBounds()
            forgetIfOverCap(session, params)
            inprocessing?.onRestart(session, params)
            params.pooledSolutionSupplier?.invoke()?.takeIf { it !== lastPooledSolution }?.let { pooled ->
                lastPooledSolution = pooled
                brancher.importPooledSolution(pooled)
            }
            return SearchRunDisposition.Continue
        }

        override fun onCancellation(context: SearchContext): SearchRunDisposition {
            params.clauseExchange?.onSearchEnd(session)
            return if (pausable) SearchRunDisposition.Pause else SearchRunDisposition.Indeterminate
        }
    }

    private fun applyObjectiveBound(): SearchRunDisposition {
        session.installObjectiveBoolBound(objective.boolWeights)
        return if (assertObjectiveBoundAtRoot() || tightenOpenColumnsAtRoot()) {
            SearchRunDisposition.Exhausted
        } else {
            SearchRunDisposition.Continue
        }
    }
}
