package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.backtrack.lp.LpAutoConfig
import com.eignex.klause.solver.backtrack.lp.LpEngine
import com.eignex.klause.solver.backtrack.lp.harvestRootCuts
import com.eignex.klause.solver.backtrack.lp.lbTreeSearch
import com.eignex.klause.solver.backtrack.lp.lpBranchPick
import com.eignex.klause.solver.backtrack.lp.lpFeasibilityPump
import com.eignex.klause.solver.backtrack.lp.lpRoundingProbe
import com.eignex.klause.solver.backtrack.lp.rootLpRelaxationBound
import com.eignex.klause.solver.backtrack.lp.shaveObjectiveLb
import com.eignex.klause.solver.backtrack.lp.shaveVariableBounds
import com.eignex.klause.solver.backtrack.selector.VarRef
import com.eignex.klause.solver.factor.bool.Clause
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.ConflictAnalyzer.AnalysisResult.Learned
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.util.MutableLongIntMap
import com.eignex.kumulant.math.splitmix64
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Complete depth-first search over a [Problem]'s assignment space, driven by propagation
 * via [PropagationSession]. Variable selection and value selection are plug-in heuristics
 * via [BacktrackParams.variableSelector] / [BacktrackParams.valueSelector] — same split
 * MiniZinc uses for `solve :: int_search(vars, var_strategy, value_strategy, complete)`.
 *
 *  - [solve] — first witness as [SolveResult.Sat], [SolveResult.Unsat] when the tree is
 *    fully explored, [SolveResult.Unknown] on [BacktrackParams.maxDecisions] exhaustion.
 *  - [samples] — yields every SAT leaf reached during traversal (each one distinct).
 *  - [enumerate] — same as [samples] plus the rolling-window Hamming-distance filter.
 *  - [minimize] — enumerates feasible assignments and returns the lowest-scoring one.
 *    Complete but exponential.
 *
 *  Complete enumeration on `n` unpinned bools walks up to `2^n` branches. Use
 *  [BacktrackParams.maxDecisions] to cap exploration on large problems.
 */
class BacktrackSolver(override val problem: Problem) :
    Solver<BacktrackParams>,
    Optimizer<BacktrackParams>,
    ResumableOptimizer<BacktrackParams> {

    /** Open an explicit-state, pausable branch-and-bound over [objective] (#381). See [ResumableSearch].
     *  The handle reuses this solver's search primitives; [params] should carry the
     *  [BacktrackParams.objectiveBoundSupplier] for external bound sharing (its [BacktrackParams.cancellation]
     *  is superseded per slice). */
    override fun resumable(objective: LinearObjective, params: BacktrackParams): ResumableSearch =
        ResumableMinimize(objective, params)

    override fun solve(params: BacktrackParams): SolveResult {
        val sink = SolveStatsSink(backend = "backtrack")
        sink.start()
        for (outcome in driveSearch(params, sink = sink)) {
            sink.stop()
            val stats = sink.snapshot()
            return when (outcome) {
                is SearchOutcome.Found -> SolveResult.Sat(outcome.sample, stats)

                is SearchOutcome.Exhausted -> SolveResult.Unsat(
                    core = outcome.core,
                    stats = stats,
                    assumptionCore = projectTouchedToAssumptions(params.assumptions, outcome.touchedAssumptionLevels),
                )

                SearchOutcome.BudgetCapped -> {
                    sink.timedOut = true
                    SolveResult.Unknown(TerminationReason.BudgetExhausted, sink.snapshot())
                }
            }
        }
        sink.stop()
        return SolveResult.Unsat(stats = sink.snapshot())
    }

    /**
     * Independent random samples ("with replacement", per the [com.eignex.klause.solver.Solver.samples]
     * contract). Each yield kicks off a fresh DFS from root on a new [PropagationSession]
     * with a per-call RNG seed; no engine state carries between yields, so subsequent
     * yields are statistically independent given the random heuristic defaults.
     *
     * **Reproducibility.** With a fixed [BacktrackParams.randomSeed] the per-call seeds
     * are derived by a deterministic LCG advance, so the same parent seed produces the
     * same sequence of samples across runs. This is reproducibility, not correlation —
     * the per-call seeds are independent random draws as far as the search is concerned.
     *
     * **Duplicates.** The sequence does **not** filter duplicates. For a problem with N
     * feasible models, the same model may be yielded multiple times; the distribution
     * across yields is determined by the heuristics. For distinct samples use [enumerate]
     * (complete + DFS-ordered) or `samples(p).distinct().take(n)` (random + distinct,
     * uses memory linear in yielded count).
     *
     * **Termination.** The sequence is **infinite for any feasible problem** — callers
     * must bound it with `.take(n)` or `.takeWhile(...)`. It terminates early only when:
     *  - a run returns [SolveResult.Unsat] — the entire search tree exhausts without a
     *    SAT (the problem is infeasible); or
     *  - a run returns [SolveResult.Unknown] — [BacktrackParams.maxDecisions] elapsed
     *    before any SAT was found on that run.
     */
    override fun samples(params: BacktrackParams): Sequence<Sample> = sequence {
        var seed = params.randomSeed ?: Random.Default.nextLong()
        while (true) {
            val perCall = params.copy(randomSeed = seed)
            when (val r = solveOnce(perCall)) {
                is SolveResult.Sat -> yield(r.assignment)
                is SolveResult.Unsat -> return@sequence
                is SolveResult.Unknown -> return@sequence
            }
            // LCG advance for reproducibility: same parent seed → same per-call seed
            // sequence → same sample sequence. The per-call seeds drive the heuristics'
            // random choices; from the search's perspective they're independent draws.
            seed = seed * 6364136223846793005L + 1442695040888963407L
        }
    }

    private fun solveOnce(params: BacktrackParams): SolveResult {
        for (outcome in driveSearch(params)) {
            return when (outcome) {
                is SearchOutcome.Found -> SolveResult.Sat(outcome.sample)
                is SearchOutcome.Exhausted -> SolveResult.Unsat(outcome.core)
                SearchOutcome.BudgetCapped -> SolveResult.Unknown(TerminationReason.BudgetExhausted)
            }
        }
        return SolveResult.Unsat()
    }

    /**
     * Distinct SAT assignments via single-DFS traversal of the search tree. Complete:
     * given enough budget, every distinct feasible assignment is yielded exactly once.
     * The optional rolling Hamming-distance window adds extra spacing between yields.
     *
     * For *diverse* distinct samples — useful when a small test/verification budget
     * shouldn't be spent on one subtree — call [samples] (which uses random restarts
     * with-replacement) and de-duplicate client-side, e.g. `samples(p).distinct().take(n)`.
     */
    override fun enumerate(params: BacktrackParams): Sequence<Sample> = sequence {
        val window = ArrayDeque<Sample>()
        for (outcome in driveSearch(params)) {
            when (outcome) {
                is SearchOutcome.Found -> {
                    val snap = outcome.sample
                    if (farEnough(snap, window, params.minHammingDistance)) {
                        yield(snap)
                        if (params.recentWindow > 0) {
                            if (window.size >= params.recentWindow) window.removeFirst()
                            window.addLast(snap)
                        }
                    }
                }

                is SearchOutcome.Exhausted, SearchOutcome.BudgetCapped -> return@sequence
            }
        }
    }

    /**
     * Branch-and-bound minimisation. Walks the DFS yielding feasible leaves; each leaf
     * improves the incumbent `bestObj` and tightens a partial-assignment lower bound
     * that the search engine consults on every successful pin to prune the subtree when
     * it provably can't beat the incumbent. The pruning predicate closes over the
     * mutable `bestObj`, so the tightening propagates lazily without explicit
     * communication into the engine.
     *
     * For [LinearObjective] the bound is `Σ_b lb_b(bool) + Σ_i lb_i(int) + constant`,
     * where:
     *  - `lb_b = boolWeights[b]` if `b` is pinned-true, `0` if pinned-false,
     *    `min(0, boolWeights[b])` if unpinned;
     *  - `lb_i = coeff[i] · (coeff ≥ 0 ? dom.min : dom.max)`.
     *
     * Sound: every completion can only *raise* the contribution of unpinned vars from
     * the minimum, so an LB that already equals or exceeds the incumbent guarantees no
     * descendant leaf beats it.
     */
    override fun minimize(objective: LinearObjective, params: BacktrackParams): MinimizeResult =
        improvements(objective, params).last()

    /**
     * Anytime variant of [minimize]: yields one [MinimizeResult.BestFound] per new
     * incumbent discovered, followed by exactly one terminal verdict
     * ([MinimizeResult.Optimal] / [MinimizeResult.Infeasible] / final
     * [MinimizeResult.BestFound] / [MinimizeResult.Unknown]). Same B&B engine as
     * [minimize]; just exposes the search's intermediate bests as they land instead of
     * collapsing them into a single return value.
     *
     * With [BacktrackParams.lpConfig] the LP-relaxation family is enabled here, structurally:
     * [LpAutoConfig.resolve] ORs on the techniques the emphasis permits whose target structure the
     * problem contains. The objective is statically linear, so no objective-shape check is involved —
     * LP enablement is purely a params decision.
     */
    override fun improvements(objective: LinearObjective, params: BacktrackParams): Sequence<MinimizeResult> =
        sequence {
            // The single B&B orchestration ([ResumableMinimize]), driven lazily: one incumbent surfaced
            // per step, then the terminal verdict. lpConfig is resolved inside the search. `pausable = false`
            // makes a fired cancellation a hard terminal stop (no resume) — a one-shot stream's contract.
            val search = ResumableMinimize(objective, params, pausable = false)
            while (true) {
                when (val event = search.runUntilEvent()) {
                    is StepEvent.Incumbent -> yield(event.result)

                    is StepEvent.Terminal -> {
                        yield(event.result)
                        break
                    }

                    StepEvent.Paused -> break // unreachable when pausable = false
                }
            }
        }

    /** One step of [ResumableMinimize.runUntilEvent]: a new incumbent, the terminal verdict, or a
     *  slice-boundary pause (only in pausable mode). */
    private sealed interface StepEvent {
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
     * ([runActive] keeps a mid-run resume from re-initialising the run).
     *
     * Two callers drive it, differing only in [pausable]:
     *  - [improvements] streams it lazily — one [StepEvent.Incumbent] yielded per call, then the terminal;
     *    a fired cancellation is a hard stop.
     *  - [runSlice] runs it one time slice at a time for [com.eignex.klause.portfolio.SequentialPortfolio]
     *    (#381): a fired slice deadline pauses ([StepEvent.Paused]); a later call resumes mid-tree so the
     *    arm never cold-restarts.
     */
    private inner class ResumableMinimize(
        private val objective: LinearObjective,
        params0: BacktrackParams,
        // true: a fired cancellation is a slice boundary the caller may resume past ([runSlice]); false:
        // it is a hard terminal stop ([improvements] one-shot). The single difference between the two
        // ways this one engine is driven.
        private val pausable: Boolean = true,
    ) : ResumableSearch {
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

        /** Steer [cancelCheckInterval] so the wall-clock gap between deadline polls hovers around
         *  [CANCEL_CHECK_TARGET_MS]: grow (to the [CANCEL_CHECK_INTERVAL] ceiling) when polls are cheap,
         *  shrink (to 1) when a few nodes already exceed the target. */
        private fun adaptCancelInterval() {
            val now = TimeSource.Monotonic.markNow()
            val prev = lastCancelCheckMark
            lastCancelCheckMark = now
            val elapsedMs = if (prev == null) 0L else (now - prev).inWholeMilliseconds
            if (elapsedMs > CANCEL_CHECK_TARGET_MS) {
                if (cancelCheckInterval > 1) cancelCheckInterval = maxOf(1, cancelCheckInterval / 2)
            } else if (cancelCheckInterval < CANCEL_CHECK_INTERVAL) {
                cancelCheckInterval = minOf(CANCEL_CHECK_INTERVAL, cancelCheckInterval * 2)
            }
        }

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
        private val boolPhaseTracking = params.phaseSaving || params.targetPhasing
        private val boolPhase: BooleanArray? = if (boolPhaseTracking) BooleanArray(problem.numBoolVars) else null
        private val boolPhaseSet: BooleanArray? = if (boolPhaseTracking) BooleanArray(problem.numBoolVars) else null
        private val intPhase: IntArray? = if (params.phaseSaving) IntArray(problem.numIntVars) else null
        private val intPhaseSet: BooleanArray? = if (params.phaseSaving) BooleanArray(problem.numIntVars) else null
        private val boolTarget: BooleanArray? = if (params.targetPhasing) BooleanArray(problem.numBoolVars) else null
        private val boolTargetSet: BooleanArray? = if (params.targetPhasing) BooleanArray(problem.numBoolVars) else null
        private var bestTrailSize = -1
        private var rephaseMode = RephaseMode.TARGET
        private var conflictsSinceRephase = 0L
        private val onConflictTick: () -> Unit = tick@{
            if (boolTarget == null) return@tick
            conflictsSinceRephase++
            if (conflictsSinceRephase >= params.rephaseInterval) {
                conflictsSinceRephase = 0
                rephaseMode = rephaseMode.next()
            }
        }
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
        private var cancelCheckCountdown = 0

        // Time-adaptive cancellation cadence: the deadline is polled every [cancelCheckInterval] nodes,
        // and the interval is steered so the wall-clock gap between polls hovers around
        // [CANCEL_CHECK_TARGET_MS]. A fixed node count can't bound that gap — per-node cost spans sub-µs
        // (pure SAT) to ~0.5s (heavy global propagation). Starts at 1 so the first gap can't be a full
        // batch of expensive nodes; fast instances grow it to the [CANCEL_CHECK_INTERVAL] ceiling.
        private var cancelCheckInterval = 1
        private var lastCancelCheckMark: TimeSource.Monotonic.ValueTimeMark? = null
        private var runActive = false
        private var started = false

        /** Terminal verdict once the search completes; null while still pending. */
        private var done: MinimizeResult? = null
        override val isDone: Boolean get() = done != null

        // Root-unsat (bake / seed) is decided once at construction: the search is over before it starts.
        init {
            val baked = problem.baked
            if (baked is PropagationResult.Unsat) {
                rootCore = coreOf(baked)
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
                    rootCore = coreOf(seedResult)
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
         * One-shot pre-search LP work: harvest the global cut pool and capture the root relaxation
         * bound for the integrality-gap metric (search only bounds from level 1 down, so this is the
         * sole root capture). Run from [runUntilEvent]'s `started` guard — not at construction — so the
         * live cancellation gates the LP solves it issues. Idempotent via that guard. [token] is the
         * shared root-LP budget (#31) — the slice/global cancellation time-boxed to `LpPlan.rootBudgetFraction`.
         */
        private fun initRootLp(token: Cancellation) {
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
            sink.observeRootLpBound(
                0,
                lpEngine.rootLpRelaxationBound(relaxer, lpEngine.lpGlobalCuts, token),
            )
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
         *  Visible to the enclosing solver (which streams it from [improvements]); the class itself is
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
                // Objective shaving (#E3): raise the objective's proven lower bound before search when
                // the LP + propagation prove lower values infeasible. Sound — every raise is a proof —
                // so tightening the root session here only strengthens pruning.
                if (lpEngine.params.lpPlan.objectiveShaving) {
                    singleObj?.let { obj ->
                        lpEngine.shaveObjectiveLb(obj.varId, obj.ascending, rootToken)?.let { lb ->
                            session.implyIntAtLeast(obj.varId, lb)
                        }
                    }
                }
                // Variable shaving (#E3): tighten integer domains the LP + propagation prove cannot reach
                // their declared bounds. Sound — each tightening is a proof — so applying it to the root
                // session only strengthens pruning.
                if (lpEngine.params.lpPlan.variableShaving) {
                    for (sb in lpEngine.shaveVariableBounds(rootToken)) {
                        session.implyIntAtLeast(sb.varId, sb.lo)
                        session.implyIntAtMost(sb.varId, sb.hi)
                    }
                }
                // LP-rounding primal heuristic (#287): seed an incumbent before search so the bound
                // prunes and reduced-cost fixing bite from the first node.
                if (lpEngine.params.lpPlan.probe && lpEngine.lpRelaxer != null) {
                    // Single-shot rounding first; if it can't land a feasible point, pump toward one.
                    val seed = lpEngine.lpRoundingProbe(objective, rootToken)
                        ?: lpEngine.lpFeasibilityPump(objective, rootToken)
                    if (seed != null) recordIfImproving(seed, objective.evaluate(seed))?.let { return it }
                }
                // Best-bound tree-search primal subsolver (#E2): dive best-first for an incumbent. Pure
                // heuristic — the returned assignment is propagation-feasible and re-evaluated here.
                if (lpEngine.params.lpPlan.lbTreeSearch && lpEngine.lpRelaxer != null) {
                    lpEngine.lbTreeSearch(objective, rootToken)?.let { seed ->
                        recordIfImproving(seed, objective.evaluate(seed))?.let { return it }
                    }
                }
            }
            outer@ while (true) {
                if (!runActive) {
                    perRunBudget = if (glucose != null) {
                        Long.MAX_VALUE
                    } else {
                        params.lubyRestartBase?.let { base ->
                            val limit = lubyN(lubyIdx)
                            if (limit > Long.MAX_VALUE / base) Long.MAX_VALUE else limit * base
                        } ?: Long.MAX_VALUE
                    }
                    decisionsThisRun = 0
                    descend = true
                    runActive = true
                }
                inner@ while (true) {
                    if (cancelCheckCountdown-- <= 0) {
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
                        adaptCancelInterval()
                        cancelCheckCountdown = cancelCheckInterval
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
                        // Reduced-cost-average branching (#E1): prefer the LP's most cost-impactful
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
                        val phased = applyPhase(
                            varRef, values, boolPhase, boolPhaseSet, intPhase, intPhaseSet,
                            boolTarget, boolTargetSet, rephaseMode, rng,
                        )
                        val ordered = lpEngine.lpHints?.order(varRef, phased) ?: phased
                        val node = makeNode(varRef, ordered)
                        val decsBefore = decisionsLeft
                        val out = advance(
                            node, session, params, pruneIf,
                            { decisionsLeft }, { decisionsLeft-- },
                            sink, relearnTripped, onConflictTick, pruneLearned,
                        )
                        decisionsThisRun += decsBefore - decisionsLeft
                        when (out) {
                            AdvanceOutcome.Success -> {
                                capturePhase(varRef, session, boolPhase, boolPhaseSet, intPhase, intPhaseSet)
                                trail.add(node)
                                sink.observeNode(trail.size)
                                if (boolTarget != null && boolTargetSet != null && trail.size > bestTrailSize) {
                                    bestTrailSize = trail.size
                                    captureTargetPhase(session, boolTarget, boolTargetSet)
                                }
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
                                    backjumpAndLearn(
                                        out.learned, trail, session, params,
                                        boolPhase, boolPhaseSet, intPhase, intPhaseSet, alignFirst = false,
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
                        val out = advance(
                            top, session, params, pruneIf,
                            { decisionsLeft }, { decisionsLeft-- },
                            sink, relearnTripped, onConflictTick, pruneLearned,
                        )
                        decisionsThisRun += decsBefore - decisionsLeft
                        when (out) {
                            AdvanceOutcome.Success -> {
                                capturePhase(top.varRef, session, boolPhase, boolPhaseSet, intPhase, intPhaseSet)
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
                                    backjumpAndLearn(
                                        out.learned, trail, session, params,
                                        boolPhase, boolPhaseSet, intPhase, intPhaseSet, alignFirst = true,
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
                }
            }
            params.clauseExchange?.onRestart(session)
            params.cutExchange?.let { lpEngine.exchangeCuts(it) }
            if (assertObjectiveBoundAtRoot()) return terminalExhausted()
            params.variableSelector.onRestart()
            params.valueSelector.onRestart()
            forgetIfOverCap(session, params)
            if (vivifyEnabled) vivifyCursor = vivify(session, params, vivifyCursor)
            lubyIdx++
            sink.observeRestart()
            params.onEvent?.invoke(SearchEvent.Restart(lubyIdx - 1, decisionsThisRun))
            return null
        }
    }
}

/** Ceiling on the adaptive cancellation cadence (nodes between deadline polls). Fast instances
 *  settle here — a few microseconds per check at worst; slow ones adapt below it. See
 *  `ResumableMinimize.adaptCancelInterval`. */
internal const val CANCEL_CHECK_INTERVAL: Int = 256

/** Target wall-clock gap (ms) between deadline polls; the adaptive cadence steers toward it so `-t`
 *  overshoot stays ~this small regardless of per-node cost. */
internal const val CANCEL_CHECK_TARGET_MS: Long = 5

/** Most Gomory cuts to draw from one tableau per separation round (#22). */
internal const val GOMORY_CUTS_PER_ROUND: Int = 8

/** Separation rounds when harvesting the persistent root cut pool. */
internal const val CUT_POOL_ROUNDS: Int = 8

/** Separation rounds per during-search node (#41) — fewer than the root harvest, since the node solve
 *  repeats deeper in the tree. */
internal const val SEARCH_CUT_ROUNDS: Int = 4

/** Cap on cascading CDB backjumps within a single search step. Defensive; under
 *  a well-formed analyzer the loop terminates well before this. */
internal const val MAX_CASCADING_BACKJUMPS: Int = 64

/** After this many identical re-derivations of one clause, its conflicts are
 *  handled chronologically instead of by backjump — a repeat-learning streak this
 *  long means the backjump + assert cycle is not progressing. Generous enough that
 *  healthy re-learning (after forgetting or restarts) never trips it. */
internal const val RELEARN_FALLBACK_THRESHOLD: Int = 8
