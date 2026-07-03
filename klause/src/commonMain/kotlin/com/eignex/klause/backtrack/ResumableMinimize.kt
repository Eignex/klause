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
import com.eignex.klause.util.MutableLongIntMap
import com.eignex.kumulant.math.splitmix64
import kotlin.math.ceil
import kotlin.random.Random
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
 * Explicit-state branch-and-bound — the **single** B&B orchestration in this solver. It holds the
 * entire search state as **object fields** (not a `sequence{}` coroutine frame): the live
 * [PropagationSession] (learned clauses + DFS trail), heuristics, incumbent, phase saving and LP
 * warm-start caches. [runUntilEvent] advances the search to the next event (incumbent / terminal /
 * pause) and returns; because the inner DFS loop is a sequence of atomic steps gated by a top-of-loop
 * check, returning there and re-entering from the top is a faithful resume given the retained fields
 * (`runActive` keeps a mid-run resume from re-initialising the run).
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
    private fun sliceCancelled(): Boolean = if (pausable) {
        globalToken() || (sliceEnd?.hasPassedNow() ?: false)
    } else {
        baseCancellation()
    }

    private val poller = DeadlinePoller()

    /** Root-level infeasibility core (bake / seed), carried into the Infeasible terminal. */
    private var rootCore: UnsatCore? = null

    // --- Incumbent + objective-bound propagation state. ---
    private var best: Sample? = null
    private var bestObj: Double = Double.POSITIVE_INFINITY
    private val singleObj = objective.singleIntObjective()
    private var objVarBest: Int? = null
    private val externalShared = params.objectiveBoundSupplier != null
    private val sink = SolveStatsSink(backend = "backtrack")

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

    // --- Engine / DFS state (was driveSearch locals), all promoted to fields so a slice can pause. ---
    private val session = PropagationSession(problem)
    private val numSeed = params.assumptions.boolKeys.size + params.assumptions.intKeys.size
    private val touchedSeedLevels = if (numSeed > 0) HashSet<Int>() else null
    private val baseSeed: Long = params.randomSeed ?: Random.Default.nextLong()
    private val rng = Random(baseSeed)
    private var decisionsLeft = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE)
    private val relearnCounts = MutableLongIntMap()
    private val relearnTripped: (Learned) -> Boolean = { learned ->
        var h = 0L
        for (lit in learned.literals) h += splitmix64(lit.toLong())
        val n = relearnCounts.addTo(h, 1)
        if (n > 1) sink.observeRelearn()
        n > RELEARN_FALLBACK_THRESHOLD
    }
    private val phase = PhaseSaving(problem.numBoolVars, problem.numIntVars, params)
    private val onConflictTick: () -> Unit = phase::onConflictTick
    private var pendingBlock: Sample? = null
    private var lastObjBoundAsserted: Int? = null
    private val glucose: GlucoseRestart? = if (params.adaptiveRestart) GlucoseRestart() else null
    private var restartRequested = false
    private val vivifyEnabled = params.vivification && params.assumptions.isEmpty
    private var vivifyCursor = 0
    private var lubyIdx = 1L

    private val trail: MutableList<TrailNode> = ArrayList()
    private var descend = true
    private var decisionsThisRun = 0L
    private var perRunBudget = Long.MAX_VALUE
    private var runActive = false
    private var started = false

    /** Terminal verdict once the search completes; null while still pending. */
    private var done: MinimizeResult? = null
    override val isDone: Boolean get() = done != null

    // Root-unsat (bake / seed) is decided once at construction: the search is over before it starts.
    init {
        val baked = problem.baked
        if (baked is PropagationResult.Unsat) {
            rootCore = solver.coreOf(baked)
            done = terminalExhausted()
        } else {
            if (params.variableSelector.tracksUnassign) {
                val heuristic = params.variableSelector
                val numBool = problem.numBoolVars
                session.unassignListener = { enc ->
                    heuristic.onUnassign(if (enc < numBool) VarRef.Bool(enc) else VarRef.IntVar(enc - numBool))
                }
            }
            val seedResult = session.seed(params.assumptions)
            if (seedResult is PropagationResult.Unsat) {
                if (touchedSeedLevels != null) {
                    for (l in seedResult.conflictLevels) if (l in 1..numSeed) touchedSeedLevels.add(l)
                }
                rootCore = solver.coreOf(seedResult)
                done = terminalExhausted()
            } else {
                // Import any nogoods already in the shared pool (cross-arm); the session persists for
                // the whole search, so this arm's own clauses are never lost between slices.
                params.clauseExchange?.onSearchStart(session)
                // The pre-search root LP work (cut harvest + bound capture) is deferred to the first
                // [runUntilEvent], where the cancellation/deadline is live — see [initRootLp].
            }
        }
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

    private fun terminalExhausted(): MinimizeResult {
        sink.stop()
        val stats = sink.snapshot()
        val b = best
        return when {
            externalShared && b != null ->
                MinimizeResult.BestFound(b, bestObj, TerminationReason.SearchExhausted, stats)

            externalShared -> MinimizeResult.Unknown(TerminationReason.SearchExhausted, stats)

            b != null -> MinimizeResult.Optimal(b, bestObj, stats)

            else -> MinimizeResult.Infeasible(rootCore, stats)
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
     *  (optimum proven). Mirrors driveSearch's local `assertObjectiveBoundAtRoot`. */
    private fun assertObjectiveBoundAtRoot(): Boolean {
        val objectiveVar = singleObj?.varId ?: return false
        val b = objVarBest ?: return false
        val ascending = singleObj.ascending
        val threshold = if (ascending) b - 1 else b + 1
        if (threshold == lastObjBoundAsserted) return false
        lastObjBoundAsserted = threshold
        return session.assertObjectiveBound(objectiveVar, threshold, atMost = ascending) is PropagationResult.Unsat
    }

    /**
     * Tighten the objective variable to the portfolio's shared lower bound. The bound is
     * a valid global lower bound on the optimum, and every feasible solution has `objVar ≥ optimum`,
     * so asserting `objVar ≥ ⌈bound⌉` removes no solution — it only strengthens this arm's propagation
     * and pruning with a floor a peer arm proved. A no-op without a single ascending objective
     * variable or a shared bound (a satisfaction arm, or a non-portfolio solve).
     */
    private fun applySharedObjectiveFloor() {
        val supplier = params.objectiveLowerBoundSupplier ?: return
        val obj = singleObj?.takeIf { it.ascending } ?: return
        val bound = supplier()
        if (!bound.isFinite()) return
        val floor = ceil(bound)
        if (floor in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) {
            session.implyIntAtLeast(obj.varId, floor.toInt())
        }
    }

    /** The highest objective floor already published to the shared manager, so a republication only
     *  fires when the bound has genuinely risen. */
    private var lastPublishedFloor = Double.NEGATIVE_INFINITY

    /**
     * Publish this arm's level-0 objective floor to the portfolio's shared lower-bound manager. At
     * decision level 0 the objective variable's domain minimum is a proven global lower bound on the
     * optimum — raised since the root by objective shaving, level-0 learned objective bounds, and any
     * imported floor — so republishing it lets a bound this arm tightens mid-search reach its peers. A
     * no-op without a single ascending objective variable, off level 0 (where the bound would be
     * node-local, not global), or when the floor has not risen.
     */
    private fun publishObjectiveFloor() {
        val sink = params.objectiveLowerBoundSink ?: return
        val obj = singleObj?.takeIf { it.ascending } ?: return
        if (session.decisionLevel != 0) return
        val floor = session.intDomain(obj.varId).min.toDouble()
        if (floor > lastPublishedFloor) {
            lastPublishedFloor = floor
            sink(floor)
        }
    }

    /**
     * Import the portfolio's shared globally-valid level-0 variable bounds, tightening this arm's
     * domains at level 0. Each shared bound holds at every solution, so importing one only ever
     * soundly tightens — never excludes a solution. A no-op off level 0 or without the suppliers.
     */
    private fun importGlobalVarBounds() {
        val lower = params.globalVarLowerSupplier ?: return
        val upper = params.globalVarUpperSupplier ?: return
        if (session.decisionLevel != 0) return
        for (v in 0 until problem.numIntVars) {
            val lo = lower(v)
            if (lo != Int.MIN_VALUE) session.implyIntAtLeast(v, lo)
            val hi = upper(v)
            if (hi != Int.MAX_VALUE) session.implyIntAtMost(v, hi)
        }
    }

    /**
     * Publish this arm's globally-valid level-0 variable tightenings — the root domains after
     * propagation and shaving, before any incumbent-relative reduced-cost fixing — so peers can import
     * them. Called once at the root (not at restarts, where level-0 domains may carry incumbent-relative
     * fixings that are not globally valid). A no-op without the sink.
     */
    private fun publishGlobalVarBounds() {
        val sink = params.globalVarBoundSink ?: return
        if (session.decisionLevel != 0) return
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            val declared = problem.intDomains[v]
            if (d.min > declared.min || d.max < declared.max) sink(v, d.min, d.max)
        }
    }

    /**
     * One-shot pre-search LP work: harvest the global cut pool and capture the root relaxation
     * bound for the integrality-gap metric (search only bounds from level 1 down, so this is the
     * sole root capture). Run from [runUntilEvent]'s `started` guard — not at construction — so the
     * live cancellation gates the LP solves it issues. Idempotent via that guard. [token] is the
     * shared root-LP budget (#31) — the slice/global cancellation time-boxed to `LpPlan.rootBudgetFraction`.
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
            sink.observeLpCuts(lpEngine.lpGlobalCuts.size)
        }
        val rootBound = lpEngine.rootLpRelaxationBound(relaxer, lpEngine.lpGlobalCuts, token)
        sink.observeRootLpBound(0, rootBound)
        // Publish the root LP bound to the portfolio's shared lower-bound manager: a sound
        // global lower bound on the optimum, so a peer arm can pair it with its own incumbent to prove
        // optimality. A NaN (no LP structure) carries no information and the sink ignores it.
        if (!rootBound.isNaN()) params.objectiveLowerBoundSink?.invoke(rootBound)
    }

    /**
     * The shared cooperative-cancellation budget for the pre-search root LP work (#31): the
     * slice/global [BacktrackParams.cancellation] OR-ed with a wall-clock deadline of
     * `LpPlan.rootBudgetFraction` of the time remaining in the current slice (capped at
     * `LpPlan.rootBudgetMillis`). When the slice end is unknown (the non-pausable one-shot path)
     * only the absolute cap applies. A non-positive fraction disables the cap — the prior behaviour.
     */
    private fun rootLpBudget(): Cancellation {
        val fraction = params.lpPlan.rootBudgetFraction
        if (fraction <= 0.0) return params.cancellation
        val cap = params.lpPlan.rootBudgetMillis
        val end = sliceEnd
        val budgetMillis = if (end != null) {
            val remaining = (end - TimeSource.Monotonic.markNow()).inWholeMilliseconds
            minOf((remaining * fraction).toLong(), cap)
        } else {
            cap
        }
        return params.cancellation or Cancellation.after(budgetMillis.coerceAtLeast(0).milliseconds)
    }

    /** Advance the search to the next reportable event (a new incumbent, the terminal verdict, or —
     *  in pausable mode — a slice-boundary pause), retaining all state so the next call resumes.
     *  Visible to the enclosing solver (which streams it from [BacktrackSolver.improvements]); the class itself is
     *  private, so nothing leaks. */
    fun runUntilEvent(): StepEvent {
        done?.let { return StepEvent.Terminal(it) }
        if (!started) {
            started = true
            sink.start()
            // Root LP work runs here, not at construction, so the cancellation is live while it solves.
            // One shared budget (#31) caps the cut harvest + root bound + probe together, so a slow
            // root relaxation cannot starve search of its first node.
            val rootToken = rootLpBudget()
            initRootLp(rootToken)
            // Objective shaving: raise the objective's proven lower bound before search when
            // the LP + propagation prove lower values infeasible. Sound — every raise is a proof —
            // so tightening the root session here only strengthens pruning.
            if (lpEngine.params.lpPlan.objectiveShaving) {
                singleObj?.let { obj ->
                    lpEngine.shaveObjectiveLb(obj.varId, obj.ascending, rootToken)?.let { lb ->
                        session.implyIntAtLeast(obj.varId, lb)
                    }
                }
            }
            // Variable shaving: tighten integer domains the LP + propagation prove cannot reach
            // their declared bounds. Sound — each tightening is a proof — so applying it to the root
            // session only strengthens pruning.
            if (lpEngine.params.lpPlan.variableShaving) {
                for (sb in lpEngine.shaveVariableBounds(rootToken)) {
                    session.implyIntAtLeast(sb.varId, sb.lo)
                    session.implyIntAtMost(sb.varId, sb.hi)
                }
            }
            applySharedObjectiveFloor()
            // Publish the root floor (post-shaving) so peers see the bound this arm proved up front.
            publishObjectiveFloor()
            // Exchange globally-valid level-0 variable tightenings: import peers' first, then publish
            // this arm's (root propagation + shaving), before any incumbent-relative fixing runs.
            importGlobalVarBounds()
            publishGlobalVarBounds()
            // LP-rounding primal heuristic (#287): seed an incumbent before search so the bound
            // prunes and reduced-cost fixing bite from the first node.
            if (lpEngine.params.lpPlan.probe && lpEngine.lpRelaxer != null) {
                // Single-shot rounding first; if it can't land a feasible point, pump toward one.
                val seed = lpEngine.lpRoundingProbe(objective, rootToken)
                    ?: lpEngine.lpFeasibilityPump(objective, rootToken)
                if (seed != null) recordIfImproving(seed, objective.evaluate(seed))?.let { return it }
            }
            // Best-bound tree-search primal subsolver: dive best-first for an incumbent. Pure
            // heuristic — the returned assignment is propagation-feasible and re-evaluated here.
            if (lpEngine.params.lpPlan.lbTreeSearch && lpEngine.lpRelaxer != null) {
                lpEngine.lbTreeSearch(objective, rootToken)?.let { seed ->
                    recordIfImproving(seed, objective.evaluate(seed))?.let { return it }
                }
            }
        }
        // Re-read the shared lower bound each slice: a peer arm may have proven a tighter floor since
        // this arm last ran. Monotone and sound, so re-asserting only strengthens pruning.
        applySharedObjectiveFloor()
        outer@ while (true) {
            if (!runActive) {
                perRunBudget = if (glucose != null) {
                    Long.MAX_VALUE
                } else {
                    params.lubyRestartBase?.let { base ->
                        val limit = solver.lubyN(lubyIdx)
                        if (limit > Long.MAX_VALUE / base) Long.MAX_VALUE else limit * base
                    } ?: Long.MAX_VALUE
                }
                decisionsThisRun = 0
                descend = true
                runActive = true
            }
            inner@ while (true) {
                if (poller.due()) {
                    if (sliceCancelled()) {
                        // Cancellation fired: publish trailing glue clauses, then either pause (a slice
                        // boundary the caller can resume past — every field is retained, and a later
                        // runUntilEvent re-enters here since runActive stays set) or stop terminally.
                        params.clauseExchange?.onSearchEnd(session)
                        if (pausable) return StepEvent.Paused
                        val t = terminalBudget()
                        done = t
                        return StepEvent.Terminal(t)
                    }
                    poller.rearm()
                }
                if (decisionsThisRun >= perRunBudget || restartRequested) {
                    val term = doRestart()
                    if (term != null) {
                        done = term
                        return StepEvent.Terminal(term)
                    }
                    runActive = false
                    continue@outer
                }
                if (descend) {
                    // Reduced-cost-average branching: prefer the LP's most cost-impactful
                    // fractional variable; null falls back to the configured selector. Advisory —
                    // any branch is sound, and the selector's event hooks still fire below.
                    val varRef = lpEngine.lpBranchPick(session) ?: params.variableSelector.pick(session, rng)
                    if (varRef == null) {
                        val snap = snapshotAssignment(session)
                        params.variableSelector.onSolution(snap)
                        params.valueSelector.onSolution(snap)
                        // Always block this leaf and backtrack; surface it as an event only when it
                        // strictly improves the incumbent.
                        pendingBlock = snap
                        descend = false
                        recordIfImproving(snap, objective.evaluate(snap))?.let { return it }
                        continue@inner
                    }
                    val values = params.valueSelector.values(session, varRef, rng)
                    val phased = phase.applyPhase(varRef, values, rng)
                    val ordered = lpEngine.lpHints?.order(varRef, phased) ?: phased
                    val node = solver.makeNode(varRef, ordered)
                    val decsBefore = decisionsLeft
                    val out = solver.advance(
                        node, session, params, pruneIf,
                        { decisionsLeft }, { decisionsLeft-- },
                        sink, relearnTripped, onConflictTick, pruneLearned,
                    )
                    decisionsThisRun += decsBefore - decisionsLeft
                    when (out) {
                        AdvanceOutcome.Success -> {
                            phase.capture(varRef, session)
                            trail.add(node)
                            sink.observeNode(trail.size)
                            phase.captureTargetIfDeeper(session, trail.size)
                        }

                        AdvanceOutcome.Exhausted -> {
                            descend = false
                            continue@inner
                        }

                        AdvanceOutcome.BudgetCapped -> {
                            val t = terminalBudget()
                            done = t
                            return StepEvent.Terminal(t)
                        }

                        is AdvanceOutcome.Backjump -> {
                            if (touchedSeedLevels != null) {
                                for (l in out.learned.decisionLevels) if (l in 1..numSeed) touchedSeedLevels.add(l)
                            }
                            if (glucose != null && glucose.recordConflict(out.learned.lbd, trail.size)) {
                                restartRequested = true
                            }
                            when (
                                solver.backjumpAndLearn(
                                    out.learned, trail, session, params, alignFirst = false,
                                )
                            ) {
                                BackjumpTerm.Resume -> {
                                    descend = true
                                    continue@inner
                                }

                                BackjumpTerm.Exhausted -> {
                                    val t = terminalExhausted()
                                    done = t
                                    return StepEvent.Terminal(t)
                                }

                                BackjumpTerm.Stuck -> {
                                    descend = false
                                    continue@inner
                                }
                            }
                        }
                    }
                } else {
                    val rootBlock = pendingBlock
                    if (rootBlock != null) {
                        pendingBlock = null
                        while (trail.isNotEmpty()) {
                            session.popLast()
                            trail.removeAt(trail.size - 1)
                        }
                        val nogood = session.assignmentNogood(rootBlock.bools, rootBlock.ints)
                        if (nogood.isNotEmpty()) {
                            val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                            if (res is PropagationResult.Unsat) {
                                val t = terminalExhausted()
                                done = t
                                return StepEvent.Terminal(t)
                            }
                        }
                        if (assertObjectiveBoundAtRoot()) {
                            val t = terminalExhausted()
                            done = t
                            return StepEvent.Terminal(t)
                        }
                        descend = true
                        continue@inner
                    }
                    if (trail.isEmpty()) {
                        val t = terminalExhausted()
                        done = t
                        return StepEvent.Terminal(t)
                    }
                    val top = trail.last()
                    session.popLast()
                    val decsBefore = decisionsLeft
                    val out = solver.advance(
                        top, session, params, pruneIf,
                        { decisionsLeft }, { decisionsLeft-- },
                        sink, relearnTripped, onConflictTick, pruneLearned,
                    )
                    decisionsThisRun += decsBefore - decisionsLeft
                    when (out) {
                        AdvanceOutcome.Success -> {
                            phase.capture(top.varRef, session)
                            descend = true
                        }

                        AdvanceOutcome.Exhausted -> {
                            trail.removeAt(trail.size - 1)
                        }

                        AdvanceOutcome.BudgetCapped -> {
                            val t = terminalBudget()
                            done = t
                            return StepEvent.Terminal(t)
                        }

                        is AdvanceOutcome.Backjump -> {
                            if (touchedSeedLevels != null) {
                                for (l in out.learned.decisionLevels) if (l in 1..numSeed) touchedSeedLevels.add(l)
                            }
                            if (glucose != null && glucose.recordConflict(out.learned.lbd, trail.size)) {
                                restartRequested = true
                            }
                            when (
                                solver.backjumpAndLearn(
                                    out.learned, trail, session, params, alignFirst = true,
                                )
                            ) {
                                BackjumpTerm.Resume -> {
                                    descend = true
                                    continue@inner
                                }

                                BackjumpTerm.Exhausted -> {
                                    val t = terminalExhausted()
                                    done = t
                                    return StepEvent.Terminal(t)
                                }

                                BackjumpTerm.Stuck -> {
                                    descend = false
                                    continue@inner
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Fold [sample] (objective [o]) into the incumbent when it strictly improves the best; fires
     *  inline telemetry and returns the event to surface, or null when it isn't an improvement. */
    private fun recordIfImproving(sample: Sample, o: Double): StepEvent.Incumbent? {
        if (o >= bestObj) return null
        bestObj = o
        best = sample
        if (singleObj != null) objVarBest = sample.ints[singleObj.varId]
        params.onEvent?.invoke(SearchEvent.Incumbent(o))
        return StepEvent.Incumbent(MinimizeResult.BestFound(sample, o, TerminationReason.BudgetExhausted))
    }

    /** Restart housekeeping (pop to root, apply blocking nogood + LP nogoods, cross-arm exchange,
     *  assert the incumbent bound, rotate heuristics, forget, vivify). Returns a terminal verdict
     *  when a root contradiction proves exhaustion, else null. Mirrors driveSearch's restart block. */
    private fun doRestart(): MinimizeResult? {
        restartRequested = false
        while (trail.isNotEmpty()) {
            session.popLast()
            trail.removeAt(trail.size - 1)
        }
        val restartBlock = pendingBlock
        if (restartBlock != null) {
            pendingBlock = null
            if (restartBlock.bools.isNotEmpty() || restartBlock.ints.isNotEmpty()) {
                val nogood = session.assignmentNogood(restartBlock.bools, restartBlock.ints)
                val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                if (res is PropagationResult.Unsat) return terminalExhausted()
            }
        }
        val lpNogoods = lpEngine.lpNogoods
        if (lpNogoods != null) {
            for (nogood in lpNogoods.drain()) {
                val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                if (res is PropagationResult.Unsat) return terminalExhausted()
                // An LP Farkas nogood is globally valid (the relaxation is infeasible regardless of
                // the incumbent), so share it with peer arms directly — its LBD = length would never
                // clear the glue export filter (#844).
                params.clauseExchange?.publishGlobal(session.asSharedClause(nogood, nogood.size))
            }
        }
        params.clauseExchange?.onRestart(session)
        params.cutExchange?.let { lpEngine.exchangeCuts(it) }
        if (assertObjectiveBoundAtRoot()) return terminalExhausted()
        // At level 0 import the shared objective lower bound (tightening this arm's objVar) and
        // republish this arm's own raised floor, so a bound proven mid-search on any arm propagates
        // through the pool.
        applySharedObjectiveFloor()
        publishObjectiveFloor()
        // Import peers' globally-valid level-0 variable tightenings (import only — not publish, since
        // level-0 domains here may carry this arm's incumbent-relative fixings, which are not global).
        importGlobalVarBounds()
        params.variableSelector.onRestart()
        params.valueSelector.onRestart()
        solver.forgetIfOverCap(session, params)
        if (vivifyEnabled) vivifyCursor = solver.vivify(session, params, vivifyCursor)
        lubyIdx++
        sink.observeRestart()
        params.onEvent?.invoke(SearchEvent.Restart(lubyIdx - 1, decisionsThisRun))
        return null
    }
}
