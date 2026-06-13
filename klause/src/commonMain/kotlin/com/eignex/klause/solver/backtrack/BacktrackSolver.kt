package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Optimizer
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.ResumableOptimizer
import com.eignex.klause.solver.ResumableSearch
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.backtrack.selector.ValueSelector
import com.eignex.klause.solver.backtrack.selector.VarRef
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.lp.AllDifferentSeparator
import com.eignex.klause.solver.lp.AssignmentObjectiveCut
import com.eignex.klause.solver.lp.Basis
import com.eignex.klause.solver.lp.CircuitSeparator
import com.eignex.klause.solver.lp.CliqueCutSeparator
import com.eignex.klause.solver.lp.CpToLpRelaxation
import com.eignex.klause.solver.lp.CumulativeEnergeticBound
import com.eignex.klause.solver.lp.Cut
import com.eignex.klause.solver.lp.CutContext
import com.eignex.klause.solver.lp.CutSeparator
import com.eignex.klause.solver.lp.DualSimplex
import com.eignex.klause.solver.lp.FloatSimplex
import com.eignex.klause.solver.lp.GccSeparator
import com.eignex.klause.solver.lp.KnapsackCoverSeparator
import com.eignex.klause.solver.lp.LagrangianBound
import com.eignex.klause.solver.lp.LpExplanation
import com.eignex.klause.solver.lp.LpModel
import com.eignex.klause.solver.lp.LpOverflowException
import com.eignex.klause.solver.lp.LpRelaxation
import com.eignex.klause.solver.lp.LpSolution
import com.eignex.klause.solver.lp.LpStatus
import com.eignex.klause.solver.lp.VarStatus
import com.eignex.klause.solver.lp.addExact
import com.eignex.klause.solver.lp.mulExact
import com.eignex.klause.solver.lp.subExact
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.ConflictAnalyzer
import com.eignex.klause.solver.propagation.ConflictAnalyzer.AnalysisResult.Learned
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.propagation.TIER_CORE
import com.eignex.klause.solver.propagation.TIER_LOCAL
import com.eignex.klause.solver.propagation.TIER_MID
import com.eignex.klause.solver.propagation.TIER_UNSET
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.solver.result.projectSeedConflictToAssumptions
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableLongIntMap
import com.eignex.kumulant.math.splitmix64
import kotlin.math.ceil
import kotlin.math.round
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
     * With [BacktrackParams.lpAuto] the LP-relaxation family is enabled here, structurally:
     * [LpAutoConfig.recommend] ORs on exactly the techniques whose target structure the problem
     * contains. The objective is statically linear, so no objective-shape check is involved —
     * LP enablement is purely a params decision.
     */
    override fun improvements(objective: LinearObjective, baseParams: BacktrackParams): Sequence<MinimizeResult> =
        sequence {
            // The single B&B orchestration ([ResumableMinimize]), driven lazily: one incumbent surfaced
            // per step, then the terminal verdict. lpAuto is resolved inside the search. `pausable = false`
            // makes a fired cancellation a hard terminal stop (no resume) — a one-shot stream's contract.
            val search = ResumableMinimize(objective, baseParams, pausable = false)
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
        // lpAuto resolves the LP-relaxation family structurally, exactly as [improvements]. The slice
        // cancellation and Hamming knobs are fixed here; the per-slice deadline is re-armed in runSlice.
        private val resolvedParams0 = if (params0.lpAuto) LpAutoConfig.recommend(problem, params0) else params0

        // The caller's own token (drives the non-pausable one-shot path); superseded by the slice in
        // pausable mode.
        private val baseCancellation: Cancellation = resolvedParams0.cancellation
        private val params: BacktrackParams = resolvedParams0
            .copy(cancellation = Cancellation { sliceCancelled() }, minHammingDistance = 0, recentWindow = 0)

        // --- Slice control (no coroutine): a re-armable deadline + the current global token. ---
        private var globalToken: Cancellation = Cancellation.Never
        private var sliceEnd: TimeSource.Monotonic.ValueTimeMark? = null
        private fun sliceCancelled(): Boolean = if (pausable) {
            globalToken() || (sliceEnd?.hasPassedNow() ?: false)
        } else {
            baseCancellation()
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

        // --- LP-relaxation family state (built once; persists across slices = rolling warm starts). ---
        private val lpRelaxer = if (params.lpBounding) {
            CpToLpRelaxation(
                problem,
                objective,
                generateCuts = params.lpCuts,
                circuitArcs = params.lpCircuit,
                elementHull = params.lpElement,
                tableHull = params.lpTable,
                cumulative = params.lpCumulative,
            )
        } else {
            null
        }
        private val lpSeparators: List<CutSeparator> = if (params.lpCuts || params.lpCircuit) {
            buildList {
                if (params.lpCuts) {
                    add(AllDifferentSeparator())
                    add(GccSeparator())
                    add(KnapsackCoverSeparator())
                    add(CliqueCutSeparator())
                    val coef = LongArray(problem.numIntVars) { objective.intCoefficients.getOrElse(it) { 0L } }
                    add(AssignmentObjectiveCut(coef))
                }
                if (params.lpCircuit) add(CircuitSeparator())
            }
        } else {
            emptyList()
        }
        private val lpGlobalCuts: List<Cut> =
            if (params.lpCutPool && lpRelaxer != null && lpSeparators.isNotEmpty()) {
                harvestRootCuts(lpRelaxer, PropagationSession(problem), lpSeparators)
            } else {
                emptyList()
            }
        private val lagBound = if (params.lagrangian) {
            LagrangianBound(problem, objective).takeIf { it.applicable }
        } else {
            null
        }
        private var lagMultipliers = LongArray(lagBound?.multiplierCount ?: 0)
        private val energeticBound = if (params.energeticReasoning) {
            CumulativeEnergeticBound(problem).takeIf { it.applicable }
        } else {
            null
        }
        private var lpCheckCounter = 0
        private var energeticCheckCounter = 0
        private val lpNogoods: LpNogoodPool? = if (params.lpLearn) LpNogoodPool() else null
        private val lpBasisByDepth = ArrayList<Basis?>()
        private var lpHotTableau: DualSimplex? = null
        private val lpHints = if (params.lpBranching) LpHints(problem.numIntVars, problem.numBoolVars) else null
        private var lpBackjump: Learned? = null

        private val pruneIf: (PropagationSession) -> Boolean = { session ->
            lpBackjump = null
            // Fields captured into stable locals so the null-guards below smart-cast (a `val` property
            // read across a lambda boundary does not on its own).
            val lpRelaxerL = lpRelaxer
            val lagBoundL = lagBound
            val energeticBoundL = energeticBound
            val lpNogoodsL = lpNogoods
            val externalBound = params.objectiveBoundSupplier?.invoke() ?: Double.POSITIVE_INFINITY
            val effectiveBound = if (externalBound < bestObj) externalBound else bestObj
            when {
                linearLowerBound(objective, session) >= effectiveBound -> true

                energeticBoundL != null && ++energeticCheckCounter % params.energeticEvery == 0 &&
                    energeticBoundL.isInfeasible(session) -> {
                    sink.observeEnergeticPrune()
                    if (lpNogoodsL != null) energeticBoundL.explain(session)?.let { lpNogoodsL.add(it) }
                    true
                }

                lagBoundL != null && run {
                    val res = lagBoundL.computeBound(
                        session,
                        effectiveBound,
                        lagMultipliers,
                        params.lagrangianIterations,
                    )
                    if (res != null) {
                        lagMultipliers = res.multipliers
                        if (res.prune) sink.observeLagrangianPrune()
                        res.prune
                    } else {
                        false
                    }
                } -> true

                lpRelaxerL != null &&
                    session.decisionLevel <= params.lpBoundMaxDepth &&
                    ++lpCheckCounter % params.lpBoundEvery == 0 -> {
                    val depth = session.decisionLevel
                    val warm = if (params.lpWarmStart && depth - 1 in lpBasisByDepth.indices) {
                        lpBasisByDepth[depth - 1]
                    } else {
                        null
                    }
                    val outcome = lpBoundAndFix(
                        lpRelaxerL, session, effectiveBound, sink, warm, params, lpSeparators, lpHints,
                        objectiveVar = singleObj?.varId ?: -1,
                        objectiveAscending = singleObj?.ascending ?: true,
                        globalCuts = lpGlobalCuts, seedTableau = lpHotTableau,
                    )
                    if (outcome.basis != null) {
                        while (lpBasisByDepth.size <= depth) lpBasisByDepth.add(null)
                        lpBasisByDepth[depth] = outcome.basis
                    }
                    if (outcome.tableau != null) lpHotTableau = outcome.tableau
                    val explanation = outcome.explanation
                    if (explanation != null) {
                        val analyzed = session.analyzeConflictClause(explanation) as? Learned
                        if (analyzed != null && analyzed.asserting) {
                            lpBackjump = analyzed
                        } else {
                            lpNogoods?.add(
                                explanation,
                            )
                        }
                    }
                    outcome.prune
                }

                else -> false
            }
        }
        private val pruneLearned: () -> Learned? = { lpBackjump }

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
        private var rephaseMode = REPHASE_TARGET
        private var conflictsSinceRephase = 0L
        private val onConflictTick: () -> Unit = tick@{
            if (boolTarget == null) return@tick
            conflictsSinceRephase++
            if (conflictsSinceRephase >= params.rephaseInterval) {
                conflictsSinceRephase = 0
                rephaseMode = (rephaseMode + 1) % REPHASE_MODE_COUNT
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

        /** Advance the search to the next reportable event (a new incumbent, the terminal verdict, or —
         *  in pausable mode — a slice-boundary pause), retaining all state so the next call resumes.
         *  Visible to the enclosing solver (which streams it from [improvements]); the class itself is
         *  private, so nothing leaks. */
        fun runUntilEvent(): StepEvent {
            done?.let { return StepEvent.Terminal(it) }
            if (!started) {
                started = true
                sink.start()
                // LP-rounding primal heuristic (#287): seed an incumbent before search so the bound
                // prunes and reduced-cost fixing bite from the first node.
                if (params.lpProbe) {
                    val seed = lpRoundingProbe(objective)
                    if (seed != null) recordIfImproving(seed, objective.evaluate(seed))?.let { return it }
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
                        cancelCheckCountdown = CANCEL_CHECK_INTERVAL
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
                        val varRef = params.variableSelector.pick(session, rng)
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
                        val ordered = lpHints?.order(varRef, phased) ?: phased
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
            if (lpNogoods != null) {
                for (nogood in lpNogoods.drain()) {
                    val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                    if (res is PropagationResult.Unsat) return terminalExhausted()
                }
            }
            params.clauseExchange?.onRestart(session)
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

    /**
     * Sound lower bound on a [LinearObjective] given the current partial assignment in
     * [session]. Pinned vars contribute their exact value; unpinned bool vars take the
     * weight (or 0) that makes their contribution smallest; unpinned int vars take the
     * domain endpoint matching the coefficient's sign.
     */
    private fun linearLowerBound(obj: LinearObjective, session: PropagationSession): Long = try {
        var total = obj.constant
        val sp = session.problem
        val nb = minOf(sp.numBoolVars, obj.boolWeights.size)
        for (b in 0 until nb) {
            val w = obj.boolWeights[b]
            val v = session.boolValue(b)
            total = addExact(
                total,
                when {
                    v == true -> w
                    v == false -> 0L
                    w < 0L -> w
                    else -> 0L
                },
            )
        }
        val ni = minOf(sp.numIntVars, obj.intCoefficients.size)
        for (i in 0 until ni) {
            val c = obj.intCoefficients[i]
            if (c == 0L) continue
            val d = session.intDomain(i)
            total = addExact(total, mulExact(c, if (c >= 0L) d.min.toLong() else d.max.toLong()))
        }
        total
    } catch (_: LpOverflowException) {
        // A wrapped accumulation could overshoot the incumbent and prune wrongly; no bound is the
        // sound fallback.
        Long.MIN_VALUE
    }

    /** Outcome of one node LP pass: whether to prune, the basis to warm-start children from, and
     *  the solved pre-cut tableau for the cheaper seeded reload ([DualSimplex.solve]'s
     *  `seedTableau`) — same caching condition as the basis. */
    private class LpNodeOutcome(
        val prune: Boolean,
        val basis: Basis?,
        val explanation: IntArray? = null,
        val tableau: DualSimplex? = null,
    )

    /**
     * Bounded, deduplicating buffer of LP-learned Farkas nogoods (#247) awaiting registration at the
     * next restart. Dedup is by sorted-literal key so a region pruned repeatedly is learned once; the
     * cap bounds memory between restarts (a learned clause that matters most has the lowest LBD anyway,
     * and the forgetting pass governs the live DB). [drain] returns and clears the pending batch but
     * keeps the seen-set so a flushed clause is not re-queued.
     */
    private class LpNogoodPool(private val cap: Int = 4096) {
        private val seen = HashSet<String>()
        private val pending = ArrayList<IntArray>()

        fun add(nogood: IntArray) {
            if (nogood.isEmpty() || seen.size >= cap) return
            val key = nogood.sorted().joinToString(",")
            if (seen.add(key)) pending.add(nogood)
        }

        fun drain(): List<IntArray> {
            if (pending.isEmpty()) return emptyList()
            val out = ArrayList(pending)
            pending.clear()
            return out
        }
    }

    /**
     * LP-rounding primal heuristic (#287): solve the root relaxation and round its fractional point
     * into a feasible assignment by pinning each variable toward its LP value, propagating between
     * pins. A complete conflict-free pass is a feasible incumbent — propagation enforces every factor,
     * so the snapshot is sound by construction. Returns null when the LP is not optimal, a pin
     * conflicts (single pass, no backtracking), or a rounded value is not in the live domain.
     *
     * A bake-time root conflict must be checked explicitly: the bake fixpoint stops at the first
     * conflict, which can leave every variable "already pinned" — the pin loop then never observes
     * the Unsat and would snapshot a factor-violating assignment as a feasible incumbent.
     */
    private fun lpRoundingProbe(objective: LinearObjective): Sample? {
        val session = PropagationSession(problem)
        if (session.isUnsatAtRoot) return null
        val relaxation = CpToLpRelaxation(problem, objective).build(session)
        if (relaxation.model.n == 0) return null
        val solution = try {
            DualSimplex(relaxation.model).solve()
        } catch (_: LpOverflowException) {
            return null
        }
        if (solution.status != LpStatus.OPTIMAL) return null
        for (v in 0 until problem.numIntVars) {
            val d = session.intDomain(v)
            if (d.min == d.max) continue // already fixed by propagation
            val col = relaxation.intColOf[v]
            val target = if (col >= 0) round(solution.primal(col)).toInt().coerceIn(d.min, d.max) else d.min
            if (session.pinInt(v, target) is PropagationResult.Unsat) return null
        }
        for (b in 0 until problem.numBoolVars) {
            if (session.boolValue(b) != null) continue
            val col = relaxation.boolColOf[b]
            val target = col >= 0 && solution.primal(col) >= 0.5
            if (session.pinBool(b, target) is PropagationResult.Unsat) return null
        }
        return snapshotAssignment(session)
    }

    /** True when the relaxation's rounded objective bound is at least the incumbent. The checked
     *  add matters: a silent wrap on extreme data could flip into a false prune, and the enclosing
     *  overflow handler already treats a throw as "no bound". */
    private fun boundPrunes(solution: LpSolution, relaxation: LpRelaxation, bound: Double): Boolean {
        if (!bound.isFinite()) return false
        val lpBound = addExact(solution.objectiveLowerBoundCeil(), relaxation.objectiveConstant)
        return lpBound.toDouble() >= bound
    }

    /**
     * Persistent global cut pool: separate the structural separators at the root once and
     * return their cuts. Every root deduction holds at every solution, so root-separated cuts are
     * globally valid — a root Hall / cover / assignment / subtour cut stays a valid (if weaker)
     * bound at every tighter descendant — and re-adding them at every node avoids re-separating
     * them. They are re-tagged [Cut.global] accordingly (the separators can only prove globality
     * against declared domains, not against root-propagated ones). Gomory cuts are excluded (they
     * come from the live tableau in the per-node loop and are only locally valid). Each re-solve
     * warm-starts from the previous round's basis extended with the new cut slacks, like the
     * per-node cut loop.
     */
    private fun harvestRootCuts(
        relaxer: CpToLpRelaxation,
        session: PropagationSession,
        separators: List<CutSeparator>,
    ): List<Cut> {
        if (separators.isEmpty() || session.isUnsatAtRoot) return emptyList()
        val pool = HashSet<String>()
        val cuts = ArrayList<Cut>()
        try {
            var relaxation = relaxer.build(session)
            if (relaxation.model.n == 0) return emptyList()
            var simplex = DualSimplex(relaxation.model)
            var solution = simplex.solve()
            var prevRows = relaxation.model.m
            var round = 0
            while (round++ < CUT_POOL_ROUNDS && solution.status == LpStatus.OPTIMAL) {
                val prevBasis = solution.basis
                val prevSimplex = simplex
                val ctx = CutContext(problem, relaxation, solution, session)
                val fresh = separators.flatMap { it.separate(ctx) }
                    .filter { pool.add(it.key()) }
                    .map { if (it.global) it else Cut(it.cols, it.coeffs, it.rel, it.rhs, global = true) }
                if (fresh.isEmpty()) break
                cuts.addAll(fresh)
                relaxation = relaxer.build(session, cuts)
                simplex = DualSimplex(relaxation.model)
                solution = simplex
                    .solve(extendBasisWithSlacks(prevBasis, relaxation.model, prevRows), prevSimplex)
                prevRows = relaxation.model.m
            }
        } catch (_: LpOverflowException) {
            return cuts // keep whatever stayed within 64-bit determinants — still globally valid
        }
        return cuts
    }

    /**
     * LP-relaxation bounding (#20), cut generation (#22) and reduced-cost fixing (#21): build and
     * solve one exact integer LP relaxation of the live problem, optionally strengthen it with cuts,
     * then either prune this node or tighten its domains. Prunes when the relaxation is infeasible or
     * its objective bound — rounded up, since the true objective is integral — is at least the
     * incumbent. Catches determinant overflow and keeps the node soundly (a missing bound only loses
     * pruning, never correctness).
     */
    private fun lpBoundAndFix(
        relaxer: CpToLpRelaxation,
        session: PropagationSession,
        bound: Double,
        sink: SolveStatsSink,
        warmBasis: Basis?,
        params: BacktrackParams,
        separators: List<CutSeparator>,
        hints: LpHints?,
        objectiveVar: Int,
        objectiveAscending: Boolean,
        globalCuts: List<Cut>,
        seedTableau: DualSimplex?,
    ): LpNodeOutcome = try {
        lpBoundAndFixUnsafe(
            relaxer, session, bound, sink, warmBasis, params, separators, hints,
            objectiveVar, objectiveAscending, globalCuts, seedTableau,
        )
    } catch (_: LpOverflowException) {
        // Determinant growth (large cut coefficients especially, #18) can exceed 64 bits. A missing
        // bound or reduction only loses pruning, never soundness — keep the node and move on.
        LpNodeOutcome(false, null)
    }

    /** The Farkas nogood for an infeasible node LP (#247), or null when learning is off / no
     *  certificate. The clause is over absolute bound atoms, so it is globally valid and registered
     *  lazily at a restart (where its literals are no longer all-false). */
    private fun lpExplanation(
        params: BacktrackParams,
        relaxation: LpRelaxation,
        solution: LpSolution,
        session: PropagationSession,
    ): IntArray? = if (params.lpLearn) LpExplanation.infeasibilityClause(relaxation, solution, session) else null

    /**
     * Extend a basis optimal for the [prevRows]-row relaxation to the cut-augmented [model] — same
     * structural columns, the first [prevRows] rows unchanged — by seating each newly appended cut
     * row's slack as basic. The structural and prior-slack statuses carry over verbatim, so the
     * basis stays dual-feasible and the dual re-solve resumes near the optimum. Returns null if the
     * prior basis does not match the pre-cut shape (then the re-solve cold-starts; same optimum).
     */
    private fun extendBasisWithSlacks(prev: Basis, model: LpModel, prevRows: Int): Basis? {
        val n = model.n
        val newRows = model.m
        if (newRows < prevRows || prev.basicVars.size != prevRows || prev.status.size != n + prevRows) {
            return null
        }
        val basicVars = IntArray(newRows)
        prev.basicVars.copyInto(basicVars)
        val status = IntArray(model.numVars)
        prev.status.copyInto(status)
        for (i in prevRows until newRows) {
            basicVars[i] = n + i // the new row i's slack column
            status[n + i] = VarStatus.BASIC
        }
        return Basis(basicVars, status)
    }

    @Suppress("LongParameterList")
    private fun lpBoundAndFixUnsafe(
        relaxer: CpToLpRelaxation,
        session: PropagationSession,
        bound: Double,
        sink: SolveStatsSink,
        warmBasis: Basis?,
        params: BacktrackParams,
        separators: List<CutSeparator>,
        hints: LpHints?,
        objectiveVar: Int,
        objectiveAscending: Boolean,
        globalCuts: List<Cut>,
        seedTableau: DualSimplex?,
    ): LpNodeOutcome {
        var relaxation = relaxer.build(session, globalCuts)
        if (relaxation.model.n == 0) return LpNodeOutcome(false, null) // empty relaxation
        var simplex = DualSimplex(relaxation.model)
        // Float fast-path (#18): with no parent basis to warm from, a quick double-precision solve
        // supplies a candidate basis for the exact solver to certify. Sound regardless — the exact
        // solve re-optimizes to the true bound, and a bad/singular basis just cold-starts. The
        // seeded tableau reload (when compatible) supersedes both.
        val startBasis = warmBasis ?: if (params.lpFloatWarmStart) FloatSimplex(relaxation.model).basis() else null
        var solution = simplex.solve(startBasis, seedTableau)
        if (simplex.lastSolveSeeded) sink.observeLpSeeded()
        sink.observeLpPivots(solution.pivots)
        // Warm-start children from the initial (pre-cut) basis and tableau: cut rows vary per node,
        // but the base model structure is identical across nodes, so only this state transfers.
        val warmCache = if (solution.status == LpStatus.OPTIMAL) solution.basis else null
        val nodeTableau = if (solution.status == LpStatus.OPTIMAL) simplex else null
        // The most recent same-shape optimal simplex inside this node, seeding the fixpoint
        // re-solves (the cut rounds grow the row count, so they seed-fail fast and use the
        // extended-basis path instead).
        var lastSimplex = nodeTableau

        when (solution.status) {
            LpStatus.INFEASIBLE -> {
                sink.observeLpPrune()
                return LpNodeOutcome(true, null, lpExplanation(params, relaxation, solution, session))
            }

            LpStatus.UNBOUNDED -> return LpNodeOutcome(false, null)

            LpStatus.OPTIMAL ->
                if (boundPrunes(solution, relaxation, bound)) {
                    sink.observeLpPrune()
                    return LpNodeOutcome(true, warmCache, tableau = nodeTableau)
                }
        }

        // Cut rounds (#22): separate violated cuts from the LP point and re-solve. Cuts only append
        // rows (structural columns are unchanged), so with lpWarmCuts the previous round's optimal
        // basis — extended with the new rows' slacks — warm-starts the dual re-solve. Cuts are valid,
        // so infeasibility under them prunes. The cut list and warm-start state outlive the loop:
        // the fixpoint re-solves below keep the cuts (they stay valid as the box only shrinks) and
        // resume from the same basis.
        val cuts = ArrayList<Cut>()
        var prevBasis = warmCache // last optimal basis, extended each round to warm the re-solve
        var prevRows = relaxation.model.m // row count whose slacks `prevBasis` already covers
        if (separators.isNotEmpty()) {
            val pool = HashSet<String>()
            var round = 0
            // The root relaxation bounds the whole tree, so close it harder there (#285).
            val maxRounds = if (session.decisionLevel == 0) {
                maxOf(params.lpRootCutRounds, params.lpCutRounds)
            } else {
                params.lpCutRounds
            }
            while (round++ < maxRounds) {
                val ctx = CutContext(problem, relaxation, solution, session)
                // Structure-based separators run on the LP point; Gomory cuts come from the tableau.
                val separated = separators.flatMap { it.separate(ctx) }
                val gomory =
                    if (params.lpCuts && params.lpGomory) simplex.gomoryCuts(GOMORY_CUTS_PER_ROUND) else emptyList()
                val mir =
                    if (params.lpCuts && params.lpMir) simplex.mirCuts(GOMORY_CUTS_PER_ROUND) else emptyList()
                val fresh = (separated + gomory + mir).filter { pool.add(it.key()) }
                if (fresh.isEmpty()) break
                cuts.addAll(fresh)
                sink.observeLpCuts(fresh.size)
                relaxation = relaxer.build(session, globalCuts + cuts)
                simplex = DualSimplex(relaxation.model)
                val warmStart = if (params.lpWarmCuts && prevBasis != null) {
                    extendBasisWithSlacks(prevBasis, relaxation.model, prevRows)
                } else {
                    null
                }
                solution = simplex.solve(warmStart, lastSimplex)
                if (simplex.lastSolveSeeded) sink.observeLpSeeded()
                sink.observeLpPivots(solution.pivots)
                prevBasis = if (solution.status == LpStatus.OPTIMAL) solution.basis else null
                prevRows = relaxation.model.m
                if (solution.status == LpStatus.OPTIMAL) lastSimplex = simplex
                if (solution.status == LpStatus.INFEASIBLE) {
                    sink.observeLpPrune()
                    return LpNodeOutcome(
                        true,
                        warmCache,
                        lpExplanation(params, relaxation, solution, session),
                        tableau = nodeTableau,
                    )
                }
                if (solution.status != LpStatus.OPTIMAL) break
                if (boundPrunes(solution, relaxation, bound)) {
                    sink.observeLpPrune()
                    return LpNodeOutcome(true, warmCache, tableau = nodeTableau)
                }
            }
        }

        // Apply the LP's domain deductions and, with lpFixpoint (#283), drive the LP and propagation
        // to a joint fixpoint: re-solve and re-apply while a round keeps tightening domains (detected
        // via the session's propagation counter), capped at [LP_FIXPOINT_ITERS]. Each deduction is
        // independently sound, so iterating them is sound; cut separation is not repeated (it ran
        // above). With lpFixpoint off this is a single pass, identical to the prior behaviour.
        var iter = 0
        while (true) {
            // LP-guided value ordering (#246): record the current fractional primal for diving.
            if (solution.status == LpStatus.OPTIMAL) hints?.record(relaxation, solution)

            val before = session.propagationCount
            // Objective dual-bound propagation (#281): push the LP lower bound onto a single-variable
            // minimisation objective, with the reduced-cost certificate as the learnable reason when
            // it is expressible. When the reason is withheld (an auxiliary column or a node-local
            // row carries dual weight), the bound itself still holds at this node, so it is applied
            // as a reason-less, level-local tightening — a leaf for conflict analysis, like the
            // reason-less reduced-cost fixings.
            if (params.lpObjectiveBound && objectiveVar >= 0 && objectiveAscending &&
                solution.status == LpStatus.OPTIMAL
            ) {
                val lpFloor = addExact(solution.objectiveLowerBoundCeil(), relaxation.objectiveConstant)
                if (lpFloor in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                    val reason = LpExplanation.objectiveBoundReason(relaxation, solution, session)
                    val res = if (reason != null) {
                        session.implyIntAtLeastWithReason(objectiveVar, lpFloor.toInt(), reason)
                    } else {
                        session.implyIntAtLeast(objectiveVar, lpFloor.toInt())
                    }
                    if (res is PropagationResult.Unsat) {
                        sink.observeLpPrune()
                        return LpNodeOutcome(true, warmCache, tableau = nodeTableau)
                    }
                }
            }
            // Reduced-cost fixing (#21/#282) on the cut-strengthened solution; needs a finite gap.
            val prune = bound.isFinite() && solution.status == LpStatus.OPTIMAL &&
                applyReducedCostFixing(
                    relaxation,
                    solution,
                    session,
                    bound,
                    sink,
                    params,
                    objectiveVar,
                    objectiveAscending,
                )
            if (prune) return LpNodeOutcome(true, warmCache, tableau = nodeTableau)

            // Stop unless the joint fixpoint is enabled, this round tightened a domain, and budget remains.
            if (!params.lpFixpoint || session.propagationCount == before || ++iter >= LP_FIXPOINT_ITERS) {
                return LpNodeOutcome(false, warmCache, tableau = nodeTableau)
            }
            // Re-solve on the tightened domains and loop. The pool cuts AND this node's local cuts
            // stay in the model — fixing/propagation only shrinks the node's box, inside which the
            // local cuts remain valid — and the re-solve warm-starts from the last optimal basis
            // (identical row layout), so the re-optimisation costs a few dual pivots, not a cold solve.
            relaxation = relaxer.build(session, globalCuts + cuts)
            if (relaxation.model.n == 0) return LpNodeOutcome(false, warmCache, tableau = nodeTableau)
            simplex = DualSimplex(relaxation.model)
            val fixWarm = if (params.lpWarmCuts && prevBasis != null) {
                extendBasisWithSlacks(prevBasis, relaxation.model, prevRows)
            } else {
                null
            }
            // Same row layout as the previous round, so the seeded reload applies directly; the
            // extended basis stays as the fallback.
            solution = simplex.solve(fixWarm, lastSimplex)
            if (simplex.lastSolveSeeded) sink.observeLpSeeded()
            prevBasis = if (solution.status == LpStatus.OPTIMAL) solution.basis else null
            prevRows = relaxation.model.m
            if (solution.status == LpStatus.OPTIMAL) lastSimplex = simplex
            sink.observeLpPivots(solution.pivots)
            when (solution.status) {
                LpStatus.INFEASIBLE -> {
                    sink.observeLpPrune()
                    return LpNodeOutcome(
                        true,
                        warmCache,
                        lpExplanation(params, relaxation, solution, session),
                        tableau = nodeTableau,
                    )
                }

                LpStatus.UNBOUNDED -> return LpNodeOutcome(false, warmCache, tableau = nodeTableau)

                LpStatus.OPTIMAL -> if (boundPrunes(solution, relaxation, bound)) {
                    sink.observeLpPrune()
                    return LpNodeOutcome(true, warmCache, tableau = nodeTableau)
                }
            }
        }
    }

    /**
     * Reduced-cost fixing (#21). At the LP optimum a nonbasic variable sits at one of its bounds; to
     * move it `Δ` integer steps off that bound raises the objective by at least `|reducedCost|·Δ`.
     * Any solution improving on the incumbent has objective `≤ ceil(bound) − 1`, so a variable can
     * move at most `floor((improvingMax − lpOpt) / |reducedCost|)` steps — its opposite bound is
     * pulled in by the rest in one shot. All arithmetic is exact over the shared LP denominator, so
     * no tolerance is needed; overflow conservatively skips the column (a missed tightening is sound).
     *
     * Reductions are applied at the current decision level via [PropagationSession.implyIntAtMost] etc.,
     * so they propagate immediately and are undone on backtrack. Returns true if a reduction empties a
     * domain — the node is then infeasible and pruned.
     */
    private fun applyReducedCostFixing(
        relaxation: LpRelaxation,
        solution: LpSolution,
        session: PropagationSession,
        bound: Double,
        sink: SolveStatsSink,
        params: BacktrackParams,
        objectiveVar: Int,
        objectiveAscending: Boolean,
    ): Boolean {
        val den = solution.denominator // > 0
        val improvingMax = ceil(bound).toLong() - 1L // best objective that still beats the incumbent
        // Gap slack in scaled integer units: improvingMax·den − lpObjective(true). Non-negative here
        // because the node was not bound-pruned. Overflow on the scale-up just skips fixing.
        val slack = try {
            val objTrueNum = addExact(solution.objectiveNumerator, mulExact(relaxation.objectiveConstant, den))
            subExact(mulExact(improvingMax, den), objTrueNum)
        } catch (_: LpOverflowException) {
            return false
        }
        if (slack < 0L) return false
        val status = solution.basis.status
        // Learnable reasons for each fixing (#282): a fixing of column `col` is justified by the LP's
        // dual decomposition under the OTHER support columns' seated bounds — including the objective
        // variable's own seated bound when it carries a reduced cost — plus the incumbent bound
        // `objVar ≤ improvingMax`, plus the recorded validity premises of any non-global row carrying
        // dual weight (those justify the decomposition itself, so they are never excluded per-column).
        // Expressible only when: there is a single-var minimisation objective whose live upper bound
        // is already ≤ improvingMax (so the incumbent atom holds); every dual-weighted non-global row
        // has recorded premises (see [LpExplanation]); and no support premise sits on an auxiliary
        // column. Otherwise the fixings stay reason-less level-local tightenings, which conflict
        // analysis treats as leaves.
        var learn = params.lpLearn && objectiveVar >= 0 && objectiveAscending &&
            improvingMax in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
            session.intDomain(objectiveVar).max.toLong() <= improvingMax
        val supportCols = IntArrayList()
        val supportLits = IntArrayList()
        if (learn) {
            val seen = HashSet<Int>()
            val premLits = IntArrayList()
            if (LpExplanation.addDualRowPremiseLits(premLits, seen, relaxation, solution, session)) {
                for (k in 0 until premLits.size) {
                    supportCols.add(-1) // row premise: part of every fixing's reason, never excluded
                    supportLits.add(premLits[k])
                }
                for (c in relaxation.colVarId.indices) {
                    if (status[c] == VarStatus.BASIC) continue
                    val dNum = solution.reducedCostNumerator[c]
                    if (dNum == 0L) continue
                    // The premise side follows the reduced cost's sign, not the seat name — a
                    // collapsed (pinned) column's recorded seat is arbitrary. See
                    // [LpExplanation.premiseLit].
                    val lit = LpExplanation.premiseLit(relaxation, session, c, lowerSide = dNum > 0L)
                    if (lit == LpExplanation.PREMISE_AUX) {
                        learn = false
                        break
                    }
                    if (lit == LpExplanation.PREMISE_NONE || !seen.add(lit)) continue
                    supportCols.add(c)
                    supportLits.add(lit)
                }
            } else {
                learn = false
            }
        }
        val incumbentLit = if (learn) session.boundLeLit(objectiveVar, improvingMax.toInt(), positive = false) else 0

        // Reason for fixing `col`: every support column's seated-bound negation except col's own, plus
        // the incumbent objective bound. (col's own bound is the variable moving, not a premise.)
        fun reasonFor(col: Int): IntArray {
            val out = IntArrayList(supportCols.size + 1)
            for (k in 0 until supportCols.size) if (supportCols[k] != col) out.add(supportLits[k])
            out.add(incumbentLit)
            return out.toIntArray()
        }
        for (col in relaxation.colVarId.indices) {
            val st = status[col]
            if (st == VarStatus.BASIC) continue
            val varId = relaxation.colVarId[col]
            if (varId < 0) continue // auxiliary column (e.g. circuit arc) — no CP variable to fix
            val isBool = relaxation.colIsBool[col]
            val dNum = solution.reducedCostNumerator[col]
            if (isBool && session.boolValue(varId) != null) continue // already pinned
            val liveMin: Long
            val liveMax: Long
            if (isBool) {
                liveMin = 0L
                liveMax = 1L
            } else {
                val d = session.intDomain(varId)
                liveMin = d.min.toLong()
                liveMax = d.max.toLong()
            }
            if (liveMin == liveMax) continue
            val span = liveMax - liveMin
            val res = when (st) {
                // At lower bound: dual feasibility gives reducedCost ≥ 0; it can rise at most
                // floor(slack / d) steps before it alone overshoots the incumbent.
                VarStatus.AT_LOWER -> {
                    if (dNum <= 0L) continue
                    val dMax = slack / dNum
                    if (dMax >= span) continue
                    if (isBool) {
                        session.implyBool(
                            varId,
                            false,
                        )
                    } else if (learn) {
                        session.implyIntAtMostWithReason(varId, (liveMin + dMax).toInt(), reasonFor(col))
                    } else {
                        session.implyIntAtMost(varId, (liveMin + dMax).toInt())
                    }
                }

                // At upper bound: reducedCost ≤ 0; symmetric, tighten the lower bound.
                VarStatus.AT_UPPER -> {
                    if (dNum >= 0L) continue
                    val dMax = slack / -dNum
                    if (dMax >= span) continue
                    if (isBool) {
                        session.implyBool(
                            varId,
                            true,
                        )
                    } else if (learn) {
                        session.implyIntAtLeastWithReason(varId, (liveMax - dMax).toInt(), reasonFor(col))
                    } else {
                        session.implyIntAtLeast(varId, (liveMax - dMax).toInt())
                    }
                }

                else -> continue
            }
            if (res is PropagationResult.Unsat) {
                sink.observeLpPrune()
                return true
            }
            sink.observeLpFix()
        }
        return false
    }

    // ---------------------------------------------------------------------------------------
    // Engine.
    // ---------------------------------------------------------------------------------------

    /** Map touched-seed-level [IntArray] to the subset of [input] assumptions at those
     *  levels. Returns `null` when the input was empty (no assumption layer to
     *  project) or no level was touched (no information). */
    private fun projectTouchedToAssumptions(input: Assumptions, levels: IntArray): Assumptions? {
        if (input.isEmpty || levels.isEmpty()) return null
        // [levels] is already the touched seed-level array; the projection is idempotent over
        // duplicates, so pass it straight through (no dedup set needed).
        return projectSeedConflictToAssumptions(input, levels)
    }

    /** Convert a touched-seed-level set into a sorted-ascending [IntArray], or empty
     *  when there were no touches (or no seed in the first place). */
    private fun touchedToArray(touched: HashSet<Int>?): IntArray {
        if (touched == null || touched.isEmpty()) return IntArray(0)
        val out = touched.toIntArray()
        out.sort()
        return out
    }

    /** Lift a [PropagationResult.Unsat]'s factor-level conflict info to a klause [UnsatCore].
     *  Empty `conflictFactors` (seed-only contradiction, no factor invocation involved)
     *  collapses to `null` — the API contract is "core absent" rather than "core empty",
     *  since an empty core wouldn't be actionable. */
    private fun coreOf(unsat: PropagationResult.Unsat): UnsatCore? = if (unsat.conflictFactors.isEmpty()) {
        null
    } else {
        UnsatCore.of(unsat.conflictFactors)
    }

    private sealed interface SearchOutcome {
        data class Found(val sample: Sample) : SearchOutcome

        /** DFS exhausted without finding a model. [core] is non-null when the exhaustion
         *  was forced by root-level propagation (bake or seed); after a full DFS-tree
         *  walk, no single-factor core explains the result and [core] stays null.
         *  [touchedAssumptionLevels] is the union of seed-level decision levels that
         *  appeared in any conflict's learned-clause decision-level set during the
         *  search — feeds the assumption-core projection in
         *  [com.eignex.klause.solver.result.satisfyUnderAssumptions]. Empty when no seed was
         *  in play or no conflict referenced a seed level. */
        data class Exhausted(val core: UnsatCore? = null, val touchedAssumptionLevels: IntArray = IntArray(0)) :
            SearchOutcome
        data object BudgetCapped : SearchOutcome
    }

    /**
     * A trail frame for one variable being explored. The value iterator is supplied by the
     * caller's [ValueSelector] at node creation; [applyNext] pulls the next value, pushes
     * it into the session, and reports back both the value (so the engine can fire
     * heuristic callbacks scoped to the attempted pair) and the session's propagation
     * response. Returns `null` when the value iterator is exhausted.
     */
    private sealed interface TrailNode {
        val varRef: VarRef
        fun applyNext(session: PropagationSession): ApplyOutcome?
    }

    /** What [TrailNode.applyNext] returns: the actual value pushed (bools encoded as 0/1
     *  so the value heuristic callbacks see the original heuristic-emitted form) plus the
     *  session's [PropagationResult]. */
    private data class ApplyOutcome(val value: Int, val result: PropagationResult)

    private class BoolNode(override val varRef: VarRef.Bool, valueSeq: Sequence<Int>) : TrailNode {
        private val iter = valueSeq.iterator()
        override fun applyNext(session: PropagationSession): ApplyOutcome? {
            if (!iter.hasNext()) return null
            val v = iter.next()
            return ApplyOutcome(v, session.pinBool(varRef.varId, v != 0))
        }
    }

    /**
     * Int decisions branch on a **bound**, not an equality: `v ≤ s` then `v ≥ s+1` (or the
     * reverse). Each branch is a single bound atom, so a conflict it seeds has one literal at
     * its level and 1UIP yields an asserting clause — an equality pin (`v = k`) instead pins
     * two same-level bound atoms that 1UIP cannot collapse, which stalls conflict learning.
     * The split point `s` is the value heuristic's preferred value (clamped into `[min, max-1]`
     * so both children are non-empty); the side holding that preferred value is explored first.
     */
    private class IntNode(override val varRef: VarRef.IntVar, valueSeq: Sequence<Int>) : TrailNode {
        private val preferred: Int = valueSeq.firstOrNull() ?: 0
        private var step = 0
        private var split = 0
        private var lowerFirst = true
        private var resolved = false

        override fun applyNext(session: PropagationSession): ApplyOutcome? {
            if (!resolved) {
                val d = session.intDomain(varRef.varId)
                split = if (preferred >= d.max) d.max - 1 else maxOf(preferred, d.min)
                lowerFirst = preferred <= split
                resolved = true
            }
            val vid = varRef.varId
            return when (step++) {
                0 -> if (lowerFirst) {
                    ApplyOutcome(split, session.pinIntAtMost(vid, split))
                } else {
                    ApplyOutcome(split + 1, session.pinIntAtLeast(vid, split + 1))
                }

                1 -> if (lowerFirst) {
                    ApplyOutcome(split + 1, session.pinIntAtLeast(vid, split + 1))
                } else {
                    ApplyOutcome(split, session.pinIntAtMost(vid, split))
                }

                else -> null
            }
        }
    }

    /**
     * Lazy stream of search outcomes. Each call resumes the DFS from where it last yielded.
     * Engine invariant: `trail` lists nodes whose currently-active value is reflected in
     * `session`'s pushed pins. On Unsat, `session` self-reverts — the engine doesn't
     * popLast in that case.
     */
    private fun driveSearch(
        params: BacktrackParams,
        pruneIf: ((PropagationSession) -> Boolean)? = null,
        // Immediate LP backjump (#280): after [pruneIf] prunes a node, this returns the asserting
        // 1UIP clause derived from the node's LP infeasibility (or null). When present, [advance]
        // backjumps and learns instead of popping one level chronologically.
        pruneLearned: (() -> Learned?)? = null,
        sink: SolveStatsSink? = null,
        // Objective-bound propagation (single-variable objectives only). When [objectiveVar]
        // is set, the engine pushes each incumbent's bound onto that variable at the root —
        // `objVar ≤ best-1` for minimise ([objectiveAscending]) or `objVar ≥ best+1` for
        // maximise — as a permanent unit that propagates through the constraint defining the
        // objective. [objectiveBest] returns the objective variable's value in the current
        // incumbent, or null before one is found. Strictly stronger than the passive
        // [pruneIf] lower-bound check, and it bounds non-linear-defined objectives too.
        objectiveVar: Int = -1,
        objectiveAscending: Boolean = true,
        objectiveBest: () -> Int? = { null },
        // LP-learned Farkas nogoods (#247) pending registration; drained at each restart while the
        // trail is at root, so their bound atoms are no longer all-false. Null when learning is off.
        lpNogoods: LpNogoodPool? = null,
        // LP-guided value ordering (#246): when non-null, branch values are ordered toward the
        // variable's fractional LP value. Populated by the node LP solve via [pruneIf].
        lpHints: LpHints? = null,
    ): Sequence<SearchOutcome> = sequence {
        if (problem.baked is PropagationResult.Unsat) {
            yield(SearchOutcome.Exhausted(coreOf(problem.baked)))
            return@sequence
        }
        val session = PropagationSession(problem)
        // Bridge backtrack-time unassigns to a heuristic that removes assigned vars from its
        // order structure on pick (VSIDS): decode the combined index and re-offer the var.
        // Only wired when the heuristic opts in, so other heuristics pay no per-revert cost.
        if (params.variableSelector.tracksUnassign) {
            val heuristic = params.variableSelector
            val numBool = problem.numBoolVars
            session.unassignListener = { enc ->
                heuristic.onUnassign(if (enc < numBool) VarRef.Bool(enc) else VarRef.IntVar(enc - numBool))
            }
        }
        // Number of decision levels seed pushes uses — bool pins first then int pins.
        // Decision levels 1..numSeed correspond to assumptions; levels > numSeed are
        // post-seed DFS decisions.
        val numSeed = params.assumptions.boolKeys.size + params.assumptions.intKeys.size
        val touchedSeedLevels = if (numSeed > 0) HashSet<Int>() else null
        val seedResult = session.seed(params.assumptions)
        if (seedResult is PropagationResult.Unsat) {
            if (touchedSeedLevels != null) {
                for (l in seedResult.conflictLevels) if (l in 1..numSeed) touchedSeedLevels.add(l)
            }
            yield(SearchOutcome.Exhausted(coreOf(seedResult), touchedToArray(touchedSeedLevels)))
            return@sequence
        }
        // Phase-saving: cache the last value committed for each var (across backtracks
        // and restarts). Allocated only when enabled. The `boolPhaseSet` parallel array
        // distinguishes "never committed a value yet" from "saved value happens to be
        // false" — without it the default-false BooleanArray entries would shadow any
        // real saves of false.
        // Boolean phase saving is needed both for plain phase saving and as the fallback
        // polarity source in target phasing's SAVED rephase mode, so allocate it whenever
        // either feature is on. Integer phase saving stays gated on [phaseSaving] alone —
        // target phasing is pure-Boolean and never touches integer value selection.
        val boolPhaseTracking = params.phaseSaving || params.targetPhasing
        val boolPhase: BooleanArray? = if (boolPhaseTracking) BooleanArray(problem.numBoolVars) else null
        val boolPhaseSet: BooleanArray? = if (boolPhaseTracking) BooleanArray(problem.numBoolVars) else null
        val intPhase: IntArray? = if (params.phaseSaving) IntArray(problem.numIntVars) else null
        val intPhaseSet: BooleanArray? = if (params.phaseSaving) BooleanArray(problem.numIntVars) else null
        // Target phasing (#204): the deepest conflict-free Boolean assignment seen so far and
        // a rephasing schedule. [boolTarget]/[boolTargetSet] hold the target phase; the target
        // is refreshed whenever the trail reaches a new maximum depth (a deeper conflict-free
        // prefix). [rephaseMode] selects the current polarity source and rotates every
        // [BacktrackParams.rephaseInterval] conflicts. All persist across restarts.
        val boolTarget: BooleanArray? = if (params.targetPhasing) BooleanArray(problem.numBoolVars) else null
        val boolTargetSet: BooleanArray? = if (params.targetPhasing) BooleanArray(problem.numBoolVars) else null
        var bestTrailSize = -1
        var rephaseMode = REPHASE_TARGET
        var conflictsSinceRephase = 0L
        // Counts conflicts as they happen inside [advance] and rotates the rephase mode when
        // the interval elapses. The mode change takes effect on the next fresh descent — no
        // need to pop to root, since rephasing only reorders which polarity a new decision
        // tries first.
        val onConflictTick: () -> Unit = tick@{
            if (boolTarget == null) return@tick
            conflictsSinceRephase++
            if (conflictsSinceRephase >= params.rephaseInterval) {
                conflictsSinceRephase = 0
                rephaseMode = (rephaseMode + 1) % REPHASE_MODE_COUNT
            }
        }

        val baseSeed: Long = params.randomSeed ?: Random.Default.nextLong()
        val rng = Random(baseSeed)
        // The effective budget tightens the two limits — whichever is smaller wins. This
        // lets a uniform `maxInstructions` work across backends without removing the
        // backend-specific `maxDecisions` knob.
        var decisionsLeft = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE)

        // Failsafe against repeat-learning livelock: count identical re-derivations per
        // clause (order-free literal-set hash). Healthy re-learning happens after
        // forgetting or restarts, but an unbounded streak means the backjump + assert
        // cycle is not progressing — past the threshold those conflicts are handled
        // chronologically. The count surfaces as the `relearned` solve stat under -s.
        val relearnCounts = MutableLongIntMap()
        val relearnTripped: (Learned) -> Boolean = { learned ->
            var h = 0L
            for (lit in learned.literals) h += splitmix64(lit.toLong())
            val n = relearnCounts.addTo(h, 1)
            if (n > 1) sink?.observeRelearn()
            n > RELEARN_FALLBACK_THRESHOLD
        }

        // Outer restart loop. Each iteration is one Luby-bounded DFS run from the root.
        // When `lubyRestartBase` is null the loop runs exactly once with infinite per-run
        // budget — same as the pre-restart behaviour.
        // Assignment of the most recently yielded leaf, pending a blocking nogood. Without it
        // the DFS only steps past a found solution chronologically, and a later backjump that
        // pops those frames re-opens the leaf — the search can then revisit and re-yield it,
        // potentially forever. The nogood spans the full assignment (not the decisions) so the
        // same solution reached through a different decision order is excluded too. It is
        // registered at the root on the next backtrack (or restart) and kept permanently.
        var pendingBlock: Sample? = null
        // Objective-bound propagation: assert the incumbent bound on the objective variable
        // at the root, once per improving value. Returns true iff that makes the root
        // infeasible — the remaining objective space is empty, so the search is exhausted
        // (optimum proven). Must be called only when the session is at the root.
        var lastObjBoundAsserted: Int? = null
        fun assertObjectiveBoundAtRoot(): Boolean {
            if (objectiveVar < 0) return false
            val best = objectiveBest() ?: return false
            val threshold = if (objectiveAscending) best - 1 else best + 1
            if (threshold == lastObjBoundAsserted) return false
            lastObjBoundAsserted = threshold
            return session.assertObjectiveBound(objectiveVar, threshold, atMost = objectiveAscending) is
                PropagationResult.Unsat
        }
        // Glucose-style adaptive restart policy (#198). When enabled it replaces the Luby
        // budget: restarts fire on learned-clause quality (recent LBD vs the long-run average),
        // with trail-size blocking. `restartRequested` is set by the conflict handlers and
        // consumed at the top of the inner loop; the policy's own stats persist across restarts.
        val glucose: GlucoseRestart? = if (params.adaptiveRestart) GlucoseRestart() else null
        var restartRequested = false
        // Vivification (#203) walks the learned DB round-robin across restarts; the cursor
        // persists between restart passes so successive passes cover the whole database.
        val vivifyEnabled = params.vivification && params.assumptions.isEmpty
        var vivifyCursor = 0
        var lubyIdx = 1L
        // Cross-arm clause exchange (portfolio): import nogoods learned by prior segments/arms before
        // the first DFS run, so a re-scheduled backtrack arm starts warm instead of cold-relearning
        // every slice (#381). The session sits at the post-seed root here — the same state a restart
        // pops back to — so imported literals are free and register without an immediate unit/conflict.
        params.clauseExchange?.onSearchStart(session)
        outer@ while (true) {
            val perRunBudget: Long = if (glucose != null) {
                Long.MAX_VALUE // adaptive restarts drive the schedule; the Luby budget is off
            } else {
                params.lubyRestartBase?.let { base ->
                    // Cap multiplication to avoid overflow on tiny base + huge lubyIdx.
                    val limit = lubyN(lubyIdx)
                    if (limit > Long.MAX_VALUE / base) Long.MAX_VALUE else limit * base
                } ?: Long.MAX_VALUE
            }
            var decisionsThisRun = 0L

            val trail: MutableList<TrailNode> = ArrayList()
            var descend = true
            var cancelCheckCountdown = 0

            inner@ while (true) {
                if (cancelCheckCountdown-- <= 0) {
                    if (params.cancellation()) {
                        // Slice truncated: publish this segment's trailing glue clauses so the next
                        // segment (this arm or a sibling) imports them at its start (#381).
                        params.clauseExchange?.onSearchEnd(session)
                        yield(SearchOutcome.BudgetCapped)
                        return@sequence
                    }
                    cancelCheckCountdown = CANCEL_CHECK_INTERVAL
                }
                // Restart trigger: Luby budget hit, or the adaptive policy asked to re-pick.
                // Either way pop back to root and restart.
                if (decisionsThisRun >= perRunBudget || restartRequested) {
                    restartRequested = false
                    while (trail.isNotEmpty()) {
                        session.popLast()
                        trail.removeAt(trail.size - 1)
                    }
                    val restartBlock = pendingBlock
                    if (restartBlock != null) {
                        pendingBlock = null
                        if (restartBlock.bools.isNotEmpty() || restartBlock.ints.isNotEmpty()) {
                            // All decisions are popped; register the nogood so the restarted run
                            // cannot re-yield the same leaf. A root-level contradiction here
                            // proves the remaining space empty.
                            val nogood = session.assignmentNogood(restartBlock.bools, restartBlock.ints)
                            val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                            if (res is PropagationResult.Unsat) {
                                yield(
                                    SearchOutcome.Exhausted(
                                        touchedAssumptionLevels = touchedToArray(touchedSeedLevels),
                                    ),
                                )
                                return@sequence
                            }
                        }
                    }
                    // LP-learned Farkas nogoods (#247): the trail is at root, so each clause's bound
                    // atoms are free again. Register them permanently; a root contradiction proves the
                    // whole space empty. Globally valid (implied by the original constraints).
                    if (lpNogoods != null) {
                        val drained = lpNogoods.drain()
                        for (nogood in drained) {
                            val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                            if (res is PropagationResult.Unsat) {
                                yield(
                                    SearchOutcome.Exhausted(
                                        touchedAssumptionLevels = touchedToArray(touchedSeedLevels),
                                    ),
                                )
                                return@sequence
                            }
                        }
                    }
                    // Cross-arm clause exchange (portfolio): at root, import nogoods other arms
                    // learned and export this arm's new glue clauses. Imports register without
                    // immediate propagation (their literals are free at root) — a root contradiction
                    // surfaces on the next fixpoint, not here. No-op when not in a sharing portfolio.
                    params.clauseExchange?.onRestart(session)
                    if (assertObjectiveBoundAtRoot()) {
                        yield(SearchOutcome.Exhausted(touchedAssumptionLevels = touchedToArray(touchedSeedLevels)))
                        return@sequence
                    }
                    params.variableSelector.onRestart()
                    params.valueSelector.onRestart()
                    // LCG learned-clause forgetting: at each restart, prune the database
                    // when over [maxLearnedClauses]. Glue clauses (LBD ≤ glueThreshold)
                    // are always retained; among the rest, the lowest-LBD entries are
                    // kept up to the cap.
                    forgetIfOverCap(session, params)
                    // Vivification inprocessing: the trail is at root here, so a bounded slice
                    // of the learned DB can be strengthened against clean assumptions (#203).
                    if (vivifyEnabled) vivifyCursor = vivify(session, params, vivifyCursor)
                    lubyIdx++
                    sink?.observeRestart()
                    params.onEvent?.invoke(SearchEvent.Restart(lubyIdx - 1, decisionsThisRun))
                    continue@outer
                }
                if (descend) {
                    val varRef = params.variableSelector.pick(session, rng)
                    if (varRef == null) {
                        val snap = snapshotAssignment(session)
                        // Notify heuristics first so solution-guided variants can snapshot
                        // the incumbent before the engine continues with the next yield.
                        params.variableSelector.onSolution(snap)
                        params.valueSelector.onSolution(snap)
                        pendingBlock = snap
                        yield(SearchOutcome.Found(snap))
                        descend = false
                        continue@inner
                    }
                    val values = params.valueSelector.values(session, varRef, rng)
                    val phased = applyPhase(
                        varRef, values, boolPhase, boolPhaseSet, intPhase, intPhaseSet,
                        boolTarget, boolTargetSet, rephaseMode, rng,
                    )
                    // LP-guided diving reorders toward the LP value; no-op when no hint (#246).
                    val ordered = lpHints?.order(varRef, phased) ?: phased
                    val node = makeNode(varRef, ordered)
                    val decsBefore = decisionsLeft
                    val out = advance(
                        node,
                        session,
                        params,
                        pruneIf,
                        { decisionsLeft },
                        { decisionsLeft-- },
                        sink,
                        relearnTripped,
                        onConflictTick,
                        pruneLearned,
                    )
                    decisionsThisRun += decsBefore - decisionsLeft
                    when (out) {
                        AdvanceOutcome.Success -> {
                            capturePhase(varRef, session, boolPhase, boolPhaseSet, intPhase, intPhaseSet)
                            trail.add(node)
                            sink?.observeNode(trail.size)
                            // Target phasing: a new maximum trail depth is the deepest
                            // conflict-free assignment seen — snapshot it as the target phase.
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
                            params.clauseExchange?.onSearchEnd(session)
                            yield(SearchOutcome.BudgetCapped)
                            return@sequence
                        }

                        is AdvanceOutcome.Backjump -> {
                            sink?.observeFail()
                            sink?.observeLearn()
                            if (touchedSeedLevels != null) {
                                for (l in out.learned.decisionLevels) if (l in 1..numSeed) touchedSeedLevels.add(l)
                            }
                            // Feed the learned clause's LBD and the current depth to the
                            // adaptive restart policy (trail size == decision level here; the
                            // failed pin was self-reverted by the session).
                            if (glucose != null && glucose.recordConflict(out.learned.lbd, trail.size)) {
                                restartRequested = true
                            }
                            // Execute the backjump + learn sequence. On cascading conflict
                            // during assertion, recurse.
                            val term = backjumpAndLearn(
                                out.learned, trail, session, params,
                                boolPhase, boolPhaseSet, intPhase, intPhaseSet, alignFirst = false,
                            )
                            when (term) {
                                BackjumpTerm.Resume -> {
                                    descend = true
                                    continue@inner
                                }

                                BackjumpTerm.Exhausted -> {
                                    yield(
                                        SearchOutcome.Exhausted(
                                            touchedAssumptionLevels = touchedToArray(touchedSeedLevels),
                                        ),
                                    )
                                    return@sequence
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
                        // Apply the pending blocking nogood at the root, where it can neither
                        // conflict nor assert mid-trail; a root contradiction proves the
                        // remaining space empty.
                        pendingBlock = null
                        while (trail.isNotEmpty()) {
                            session.popLast()
                            trail.removeAt(trail.size - 1)
                        }
                        val nogood = session.assignmentNogood(rootBlock.bools, rootBlock.ints)
                        if (nogood.isNotEmpty()) {
                            val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                            if (res is PropagationResult.Unsat) {
                                yield(
                                    SearchOutcome.Exhausted(
                                        touchedAssumptionLevels = touchedToArray(touchedSeedLevels),
                                    ),
                                )
                                return@sequence
                            }
                        }
                        if (assertObjectiveBoundAtRoot()) {
                            yield(SearchOutcome.Exhausted(touchedAssumptionLevels = touchedToArray(touchedSeedLevels)))
                            return@sequence
                        }
                        descend = true
                        continue@inner
                    }
                    if (trail.isEmpty()) {
                        yield(
                            SearchOutcome.Exhausted(
                                touchedAssumptionLevels = touchedToArray(touchedSeedLevels),
                            ),
                        )
                        return@sequence
                    }
                    val top = trail.last()
                    session.popLast()
                    val decsBefore = decisionsLeft
                    val out = advance(
                        top,
                        session,
                        params,
                        pruneIf,
                        { decisionsLeft },
                        { decisionsLeft-- },
                        sink,
                        relearnTripped,
                        onConflictTick,
                        pruneLearned,
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
                            params.clauseExchange?.onSearchEnd(session)
                            yield(SearchOutcome.BudgetCapped)
                            return@sequence
                        }

                        is AdvanceOutcome.Backjump -> {
                            if (touchedSeedLevels != null) {
                                for (l in out.learned.decisionLevels) if (l in 1..numSeed) touchedSeedLevels.add(l)
                            }
                            if (glucose != null && glucose.recordConflict(out.learned.lbd, trail.size)) {
                                restartRequested = true
                            }
                            // Else-path: session has been popped below trail.last; align
                            // first (trail.removeAt) then proceed to backjump + learn.
                            val term = backjumpAndLearn(
                                out.learned, trail, session, params,
                                boolPhase, boolPhaseSet, intPhase, intPhaseSet, alignFirst = true,
                            )
                            when (term) {
                                BackjumpTerm.Resume -> {
                                    descend = true
                                    continue@inner
                                }

                                BackjumpTerm.Exhausted -> {
                                    yield(
                                        SearchOutcome.Exhausted(
                                            touchedAssumptionLevels = touchedToArray(touchedSeedLevels),
                                        ),
                                    )
                                    return@sequence
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

    /**
     * If phase-saving is on and a value is cached for [varRef], prepend the cached value
     * to the heuristic's order (and drop it from the rest of the sequence so it isn't
     * tried twice). Otherwise the heuristic's order passes through unchanged.
     */
    private fun applyPhase(
        varRef: VarRef,
        values: Sequence<Int>,
        boolPhase: BooleanArray?,
        boolPhaseSet: BooleanArray?,
        intPhase: IntArray?,
        intPhaseSet: BooleanArray?,
        boolTarget: BooleanArray? = null,
        boolTargetSet: BooleanArray? = null,
        rephaseMode: Int = REPHASE_TARGET,
        rng: Random? = null,
    ): Sequence<Int> = when (varRef) {
        is VarRef.Bool -> {
            val v = varRef.varId
            val savedFirst: Int? = if (boolPhase != null && boolPhaseSet != null && boolPhaseSet[v]) {
                if (boolPhase[v]) 1 else 0
            } else {
                null
            }
            // Target phasing rotates the polarity source; plain phase saving just uses the
            // saved value. The chosen value (if any) is tried first, with the heuristic's
            // order filling the rest.
            val preferred: Int? = if (boolTarget != null && boolTargetSet != null) {
                when (rephaseMode) {
                    // Target: the deepest conflict-free phase, falling back to saved.
                    REPHASE_TARGET -> if (boolTargetSet[v]) (if (boolTarget[v]) 1 else 0) else savedFirst

                    REPHASE_SAVED -> savedFirst

                    REPHASE_TRUE -> 1

                    REPHASE_FALSE -> 0

                    REPHASE_RANDOM -> if ((rng ?: Random.Default).nextBoolean()) 1 else 0

                    else -> savedFirst
                }
            } else {
                savedFirst
            }
            if (preferred != null) sequenceOf(preferred) + values.filter { it != preferred } else values
        }

        is VarRef.IntVar -> {
            if (intPhase != null && intPhaseSet != null && intPhaseSet[varRef.varId]) {
                val saved = intPhase[varRef.varId]
                sequenceOf(saved) + values.filter { it != saved }
            } else {
                values
            }
        }
    }

    /** Record the variable's currently-pinned value for phase-saving. Called after every
     *  successful pin (descent into a node). */
    private fun capturePhase(
        varRef: VarRef,
        session: PropagationSession,
        boolPhase: BooleanArray?,
        boolPhaseSet: BooleanArray?,
        intPhase: IntArray?,
        intPhaseSet: BooleanArray?,
    ) {
        when (varRef) {
            is VarRef.Bool -> {
                if (boolPhase != null && boolPhaseSet != null) {
                    val v = session.boolValue(varRef.varId)
                    if (v != null) {
                        boolPhase[varRef.varId] = v
                        boolPhaseSet[varRef.varId] = true
                    }
                }
            }

            is VarRef.IntVar -> {
                if (intPhase != null && intPhaseSet != null) {
                    val d = session.intDomain(varRef.varId)
                    if (d.min == d.max) {
                        intPhase[varRef.varId] = d.min
                        intPhaseSet[varRef.varId] = true
                    }
                }
            }
        }
    }

    /** Snapshot the current Boolean assignment as the target phase (the deepest conflict-free
     *  prefix). Variables not yet pinned keep their previous target entry — a deeper later
     *  descent will fill them in. */
    private fun captureTargetPhase(session: PropagationSession, boolTarget: BooleanArray, boolTargetSet: BooleanArray) {
        for (v in boolTarget.indices) {
            val value = session.boolValue(v) ?: continue
            boolTarget[v] = value
            boolTargetSet[v] = true
        }
    }

    private fun makeNode(varRef: VarRef, values: Sequence<Int>): TrailNode = when (varRef) {
        is VarRef.Bool -> BoolNode(varRef, values)
        is VarRef.IntVar -> IntNode(varRef, values)
    }

    /**
     * Luby sequence (Luby-Sinclair-Zuckerman 1993). Standard CDCL restart schedule:
     * `1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8, ...`. Closed form:
     * `lubyN(i) = 2^(k-1)` when `i = 2^k − 1` (i.e. one less than a power of two);
     * otherwise `lubyN(i − 2^(k-1) + 1)` where `k = ⌊log₂(i)⌋ + 1`.
     */
    private fun lubyN(idxIn: Long): Long {
        var i = idxIn
        var k = 1
        // Find smallest k such that 2^k > i.
        while ((1L shl k) <= i) k++
        // Equivalent to the textbook recurrence; iteratively unwound.
        while (true) {
            val pow = 1L shl (k - 1)
            if (i == (pow shl 1) - 1) return pow
            // Otherwise i < (pow << 1) - 1; recurse on (i - pow + 1).
            i = i - pow + 1
            k = 1
            while ((1L shl k) <= i) k++
        }
    }

    /**
     * What [advance] reports back to the search loop. LCG-style non-chronological
     * backjump needs the target level threaded back to the outer loop, hence the
     * sealed type rather than a plain Boolean success / failure.
     */
    private sealed interface AdvanceOutcome {
        /** A value pinned cleanly; commit the node to the trail. */
        data object Success : AdvanceOutcome

        /** Node has no more values; chronological backtrack. */
        data object Exhausted : AdvanceOutcome

        /** Decision budget hit. */
        data object BudgetCapped : AdvanceOutcome

        /** Non-chronological backjump requested. After the engine pops trail to
         *  `learned.backjumpLevel`, it materialises `learned.literals` as a `Clause`,
         *  hands it to [PropagationSession.addLearnedClause], and resumes with the new
         *  clause now constraining future search and unit-propagating the asserting
         *  literal. */
        data class Backjump(val learned: Learned) : AdvanceOutcome
    }

    private fun advance(
        node: TrailNode,
        session: PropagationSession,
        params: BacktrackParams,
        pruneIf: ((PropagationSession) -> Boolean)?,
        decisionsRemaining: () -> Long,
        decrement: () -> Unit,
        sink: SolveStatsSink? = null,
        relearnTripped: ((Learned) -> Boolean)? = null,
        onConflictTick: (() -> Unit)? = null,
        pruneLearned: (() -> Learned?)? = null,
    ): AdvanceOutcome {
        while (true) {
            if (decisionsRemaining() <= 0) return AdvanceOutcome.BudgetCapped
            decrement()
            val propsBefore = session.propagationCount
            val outcome = node.applyNext(session) ?: return AdvanceOutcome.Exhausted
            // Count every factor-forced assignment this pin triggered — including the
            // propagation done on the way to a conflict (Unsat returns below).
            sink?.observePropagation(session.propagationCount - propsBefore)
            val r = outcome.result
            if (r is PropagationResult.Unsat) {
                onConflictTick?.invoke()
                // Forward the full conflict reason record so activity-, weight-, and
                // factor-driven heuristics (VSIDS, dom/wdeg) all see exactly what they
                // need without further plumbing.
                params.variableSelector.onConflict(node.varRef, r)
                params.valueSelector.onConflict(node.varRef, outcome.value)
                // CDB: if the analyzer produced a 1UIP clause with a non-chronological
                // backjump target, signal it up. The engine pops to the backjump level and
                // then persists the clause via [PropagationSession.addLearnedClause] (see
                // [backjumpAndLearn]), so the learned nogood both forces its asserting
                // literal now and constrains all future propagation — not just the one-shot
                // jump-distance prune.
                val learned = r.learnedClause as? ConflictAnalyzer.AnalysisResult.Learned
                // Only take the non-chronological backjump when the clause is a proper
                // 1UIP (asserting) clause — popping to its backjump level then makes it
                // unit and forces the asserting literal. A non-asserting clause (e.g. the
                // two same-level bound atoms an int *equality* decision contributes, which
                // 1UIP cannot collapse) would never become unit, so asserting it is a no-op
                // and the search would re-make the same decision forever. Fall through to
                // chronological within-node value enumeration instead, which is complete.
                // Two guards before taking the backjump: a clause carrying an
                // already-true literal (a kept resolved-atom literal can be) is satisfied,
                // so the assert would be a no-op and the popped frames' untried values
                // lost for nothing; and a clause re-derived identically past the relearn
                // threshold signals a cycle the backjump isn't breaking. Either way the
                // conflict falls through to chronological within-node enumeration.
                if (learned != null &&
                    learned.asserting &&
                    learned.literals.none { session.litTruth(it) == true } &&
                    relearnTripped?.invoke(learned) != true
                ) {
                    return AdvanceOutcome.Backjump(learned)
                }
                continue
            }
            if (pruneIf != null && pruneIf(session)) {
                // Immediate LP backjump (#280): if the prune carried an asserting Farkas 1UIP clause,
                // convert this node into a non-chronological backjump-and-learn. Revert the current
                // pin first (the propagation-conflict path reaches the Backjump return with the failed
                // pin already self-reverted, so trail.size == decisionLevel; match that here), then
                // route through the same guards and handler as a propagation conflict.
                val lpLearned = pruneLearned?.invoke()
                if (lpLearned != null &&
                    lpLearned.asserting &&
                    lpLearned.literals.none { session.litTruth(it) == true } &&
                    relearnTripped?.invoke(lpLearned) != true
                ) {
                    sink?.observeLpBackjump()
                    session.popLast()
                    return AdvanceOutcome.Backjump(lpLearned)
                }
                session.popLast()
                continue
            }
            // ABS-style activity heuristics need the implied set from the just-completed
            // propagation step; only Implied carries those keys.
            if (r is PropagationResult.Implied) {
                params.variableSelector.onPropagation(r)
            }
            params.variableSelector.onCommit(node.varRef)
            params.valueSelector.onCommit(node.varRef, outcome.value)
            return AdvanceOutcome.Success
        }
    }

    /**
     * Apply the LCG forgetting policy on a Luby restart. No-op when
     * [BacktrackParams.maxLearnedClauses] is null or the learned database is already
     * under the cap. Otherwise: glue clauses (LBD ≤ [BacktrackParams.lbdGlueThreshold])
     * are kept, and among non-glue clauses we keep the lowest-LBD ones up to the
     * remaining cap. Implemented as: collect (index, lbd) pairs for non-glue clauses,
     * sort by LBD ascending, take the first `remaining` of them, plus all glue.
     */
    private fun forgetIfOverCap(session: PropagationSession, params: BacktrackParams) {
        val cap = params.maxLearnedClauses ?: return
        val learnedSize = session.learnedClauseCount
        if (learnedSize <= cap) return
        if (params.tieredLearnedDb) {
            forgetTiered(session, params, cap, learnedSize)
            return
        }
        val glueThreshold = params.lbdGlueThreshold
        // Bucket non-glue clauses by LBD and pick the lowest LBDs up to the residual
        // capacity. We do this as: compute LBD per index, sort ascending, and define
        // `keep(i, lbd) = lbd <= glueThreshold || rank(i) < remaining`.
        val nonGlue = ArrayList<IntArray>(learnedSize) // [lbd, index] pairs
        for (i in 0 until learnedSize) {
            val lbd = session.learnedClauseLbd(i)
            if (lbd > glueThreshold && !session.learnedClausePermanent(i)) nonGlue.add(intArrayOf(lbd, i))
        }
        // If all are glue, nothing to forget.
        if (nonGlue.isEmpty()) return
        val glueCount = learnedSize - nonGlue.size
        val remainingCap = (cap - glueCount).coerceAtLeast(0)
        if (nonGlue.size <= remainingCap) return // already under cap
        nonGlue.sortBy { it[0] } // ascending LBD
        val kept = IntHashSet(remainingCap)
        for (k in 0 until remainingCap) kept.add(nonGlue[k][1])
        session.forgetLearnedClauses { idx, lbd ->
            lbd <= glueThreshold || session.learnedClausePermanent(idx) || idx in kept
        }
        val dropped = nonGlue.size - remainingCap
        params.onEvent?.invoke(SearchEvent.LearnedDbSweep(kept = learnedSize - dropped, dropped = dropped))
    }

    /**
     * Three-tier reduction policy (#201). Each learned clause is classified by LBD into a
     * permanent core (LBD ≤ [BacktrackParams.lbdGlueThreshold]), a mid tier
     * (LBD ≤ [BacktrackParams.midLbdThreshold]) and a local tier; tiers persist across
     * reductions. Reuse since the last reduction (the clause detected a conflict or forced a
     * unit, tracked by `PropagationState.noteLearnedUse`) drives promotion and demotion:
     *  - core: always kept;
     *  - mid: always kept this pass, but demoted to local when idle so it can be deleted later;
     *  - local: promoted to mid when reused, otherwise a deletion candidate.
     * Among the local deletion candidates the lowest-LBD ones are kept up to the residual cap
     * and the rest are dropped. Reuse flags are cleared for survivors so the next window
     * measures fresh activity.
     */
    private fun forgetTiered(session: PropagationSession, params: BacktrackParams, cap: Int, learnedSize: Int) {
        val coreThreshold = params.lbdGlueThreshold
        val midThreshold = params.midLbdThreshold
        val locals = ArrayList<IntArray>(learnedSize) // [lbd, index] local deletion candidates
        for (i in 0 until learnedSize) {
            val lbd = session.learnedClauseLbd(i)
            val used = session.learnedClauseUsedSinceReduction(i)
            val entryTier = session.learnedClauseTier(i).let { t ->
                if (t != TIER_UNSET) {
                    t
                } else {
                    when {
                        lbd <= coreThreshold -> TIER_CORE
                        lbd <= midThreshold -> TIER_MID
                        else -> TIER_LOCAL
                    }
                }
            }
            if (session.learnedClausePermanent(i)) {
                session.setLearnedClauseTier(i, entryTier) // permanent clauses are always kept
                continue
            }
            when (entryTier) {
                TIER_CORE -> session.setLearnedClauseTier(i, TIER_CORE)

                // Mid is kept this pass; demote to local when idle so it ages out next time.
                TIER_MID -> session.setLearnedClauseTier(i, if (used) TIER_MID else TIER_LOCAL)

                else -> if (used) {
                    session.setLearnedClauseTier(i, TIER_MID) // promote a reused local clause
                } else {
                    session.setLearnedClauseTier(i, TIER_LOCAL)
                    locals.add(intArrayOf(lbd, i)) // deletion candidate
                }
            }
        }
        val kept = learnedSize - locals.size
        val residualCap = (cap - kept).coerceAtLeast(0)
        if (locals.size <= residualCap) {
            for (i in 0 until learnedSize) session.clearLearnedClauseUsed(i)
            return
        }
        locals.sortBy { it[0] } // ascending LBD: keep the lowest, drop the highest
        val dropSet = IntHashSet(locals.size - residualCap)
        for (k in residualCap until locals.size) dropSet.add(locals[k][1])
        session.forgetLearnedClauses { idx, _ -> idx !in dropSet }
        params.onEvent?.invoke(SearchEvent.LearnedDbSweep(kept = learnedSize - dropSet.size, dropped = dropSet.size))
        // Indices were compacted by the forget; reset every survivor's reuse flag.
        val survivors = session.learnedClauseCount
        for (i in 0 until survivors) session.clearLearnedClauseUsed(i)
    }

    /**
     * Clause vivification inprocessing (#203) — Piette-Hamadi-Saïs 2008. Walks a bounded
     * round-robin slice ([BacktrackParams.vivifyBatch]) of the learned-clause database and
     * strengthens each pure-Boolean, non-permanent clause via [vivifyClause]. Must be called
     * with the session at root (the restart boundary pops the DFS trail first). Strengthened
     * clauses are swapped in by dropping the originals and re-adding the shortened versions;
     * since the re-added clauses are at least binary over root-unassigned variables they don't
     * propagate, so the session is left at root. Returns the advanced cursor for the next pass.
     *
     * Soundness: every clause [vivifyClause] returns is still implied by the formula (a
     * subclause of an implied clause, or a prefix proven implied by propagation), so swapping
     * it in cannot lose models — checked by the learned-clause / witness validation tests.
     */
    private fun vivify(session: PropagationSession, params: BacktrackParams, startCursor: Int): Int {
        val count = session.learnedClauseCount
        if (count == 0) return 0
        val numBool = session.problem.numBoolVars
        val batch = params.vivifyBatch.coerceAtLeast(1)
        val replacements = ArrayList<IntArray>()
        val dropIdx = IntHashSet()
        var cursor = if (startCursor in 0 until count) startCursor else 0
        var examined = 0
        while (examined < batch && examined < count) {
            val idx = cursor
            cursor = (cursor + 1) % count
            examined++
            if (session.learnedClausePermanent(idx)) continue
            val clause = session.learnedClauseAt(idx)
            val lits = clause.literals
            // Pure-Boolean only; nothing to shorten below 3 literals (we never emit units).
            if (lits.size < 3 || !clause.allLiteralsBool(numBool)) continue
            val strengthened = vivifyClause(session, lits) ?: continue
            if (strengthened.size in 2 until lits.size) {
                dropIdx.add(idx)
                replacements.add(strengthened)
            }
        }
        if (replacements.isEmpty()) return cursor
        session.forgetLearnedClauses { i, _ -> i !in dropIdx }
        for (newLits in replacements) session.addLearnedClause(Clause(newLits), lbd = newLits.size)
        // The forget renumbered the database, so resume the round-robin from the start.
        return 0
    }

    /**
     * Vivify one clause with the session at root: walk [lits] asserting the negation of each
     * literal under propagation. A literal already falsified by the earlier negations is
     * dropped (redundant); a literal forced true, or a conflict on asserting its negation,
     * shortens the clause to the literals visited so far. Returns the strengthened literal
     * array, or null when nothing changed. Every tentative pin is reverted before returning,
     * so the session is left exactly as it was found.
     */
    private fun vivifyClause(session: PropagationSession, lits: IntArray): IntArray? {
        val keep = IntArrayList(lits.size)
        var pushed = 0
        var result: IntArray? = null
        for (li in lits) {
            when (session.litTruth(li)) {
                // The earlier negations already force li true ⇒ (kept ∨ li) is implied.
                true -> {
                    keep.add(li)
                    result = keep.toIntArray()
                    break
                }

                // li is already falsified by the earlier negations ⇒ redundant, drop it.
                false -> Unit

                // Undetermined: assert ¬li and keep going.
                null -> {
                    keep.add(li)
                    val r = session.pinBool(Lit.variable(li), !Lit.isPositive(li))
                    if (r is PropagationResult.Unsat) {
                        // ¬(kept) is unsatisfiable ⇒ (kept) is implied.
                        result = keep.toIntArray()
                        break
                    }
                    pushed++
                }
            }
        }
        repeat(pushed) { session.popLast() }
        if (result == null && keep.size < lits.size) result = keep.toIntArray()
        return result
    }

    /** How [backjumpAndLearn] terminated. */
    private enum class BackjumpTerm {
        /** Backjumped, learned clause asserted cleanly. Resume by descending. */
        Resume,

        /** Asserting the learned clause forced a level-0 contradiction; the entire search
         *  space is infeasible. Engine yields [SearchOutcome.Exhausted]. */
        Exhausted,

        /** Cascading conflicts couldn't be resolved further (e.g., assertion reached
         *  level 0 without a useful new clause). Fall back to chronological backtrack. */
        Stuck,
    }

    /**
     * Execute the CDB backjump + clause-learn sequence:
     *   - pop trail + session to `learned.backjumpLevel`;
     *   - materialise `learned.literals` as a [Clause]
     *     and feed it to [PropagationSession.addLearnedClause], which asserts it via
     *     propagation (forcing the asserting literal as a unit pin);
     *   - if the assertion cascades into another conflict, recurse on the new analyzer
     *     result. Bounded to keep the search loop from looping forever on pathological
     *     instances; [BackjumpTerm.Stuck] surfaces to the caller in that case.
     */
    private fun backjumpAndLearn(
        learned: Learned,
        trail: MutableList<TrailNode>,
        session: PropagationSession,
        @Suppress("UNUSED_PARAMETER") params: BacktrackParams,
        @Suppress("UNUSED_PARAMETER") boolPhase: BooleanArray?,
        @Suppress("UNUSED_PARAMETER") boolPhaseSet: BooleanArray?,
        @Suppress("UNUSED_PARAMETER") intPhase: IntArray?,
        @Suppress("UNUSED_PARAMETER") intPhaseSet: BooleanArray?,
        alignFirst: Boolean,
    ): BackjumpTerm {
        if (alignFirst && trail.isNotEmpty()) trail.removeAt(trail.size - 1)
        var current = learned
        // Cap the recursive backjump loop to defend against pathological cycles. Each
        // round strictly reduces the conflict level (the analyzer's backjumpLevel is
        // always < the conflict's current level), so termination is guaranteed in a
        // sane analyzer — the cap is purely defensive.
        repeat(MAX_CASCADING_BACKJUMPS) {
            // A non-asserting clause never becomes unit after the backjump, so it can't
            // force its asserting literal — fall back to chronological backtracking.
            if (!current.asserting) return BackjumpTerm.Stuck
            // Pop trail + session to the backjump level.
            while (trail.size > current.backjumpLevel) {
                session.popLast()
                trail.removeAt(trail.size - 1)
            }
            // Build the Clause and assert it. The clause's literals are non-empty as
            // long as the analyzer produced a UIP (always the case in well-formed
            // calls); if the clause came out empty, fall back to chronological.
            if (current.literals.isEmpty()) return BackjumpTerm.Stuck
            val clause = Clause(current.literals)
            val result = session.addLearnedClause(clause, current.lbd)
            when (result) {
                is PropagationResult.Implied -> return BackjumpTerm.Resume

                is PropagationResult.Unsat -> {
                    // Assertion cascaded into another conflict. The session ran the
                    // analyzer on the new conflict; if a new learned clause came back,
                    // recurse — otherwise we're stuck.
                    val next = result.learnedClause
                        as? Learned
                        ?: return BackjumpTerm.Stuck
                    // If the new backjump target is level 0 and the clause is empty
                    // after that jump, the whole problem is infeasible.
                    if (next.backjumpLevel == 0 && next.literals.isEmpty()) {
                        return BackjumpTerm.Exhausted
                    }
                    current = next
                }
            }
        }
        return BackjumpTerm.Stuck
    }

    private fun snapshotAssignment(session: PropagationSession): Sample {
        val sp = session.problem
        val bools = BooleanArray(sp.numBoolVars) { v -> session.boolValue(v) ?: false }
        val ints = IntArray(sp.numIntVars) { v -> session.intDomain(v).min }
        return Sample(bools, ints)
    }

    private fun farEnough(candidate: Sample, window: ArrayDeque<Sample>, minDistance: Int): Boolean {
        if (minDistance <= 0 || window.isEmpty()) return true
        for (p in window) if (candidate.hammingDistanceTo(p) < minDistance) return false
        return true
    }
    private companion object {
        /** Cancellation is polled this often inside the search loop. Lower = more
         *  responsive; higher = lower overhead. 256 is a few microseconds per check at
         *  worst, and the search stops within a few hundred decisions of a cancel. */
        const val CANCEL_CHECK_INTERVAL: Int = 256

        /** Most Gomory cuts to draw from one tableau per separation round (#22). */
        const val GOMORY_CUTS_PER_ROUND: Int = 8

        /** Separation rounds when harvesting the persistent root cut pool. */
        const val CUT_POOL_ROUNDS: Int = 8

        /** Maximum LP↔propagation re-solve rounds per node under `lpFixpoint` (#283). */
        const val LP_FIXPOINT_ITERS: Int = 4

        /** Cap on cascading CDB backjumps within a single search step. Defensive; under
         *  a well-formed analyzer the loop terminates well before this. */
        const val MAX_CASCADING_BACKJUMPS: Int = 64

        /** After this many identical re-derivations of one clause, its conflicts are
         *  handled chronologically instead of by backjump — a repeat-learning streak this
         *  long means the backjump + assert cycle is not progressing. Generous enough that
         *  healthy re-learning (after forgetting or restarts) never trips it. */
        const val RELEARN_FALLBACK_THRESHOLD: Int = 8

        // Rephasing polarity sources (#204), rotated every `rephaseInterval` conflicts.

        /** Bias toward the deepest conflict-free assignment seen (falls back to saved). */
        const val REPHASE_TARGET: Int = 0

        /** Plain phase saving — the last value committed for the variable. */
        const val REPHASE_SAVED: Int = 1

        /** Force all decisions to try `true` first. */
        const val REPHASE_TRUE: Int = 2

        /** Force all decisions to try `false` first. */
        const val REPHASE_FALSE: Int = 3

        /** Random polarity per decision. */
        const val REPHASE_RANDOM: Int = 4

        /** Number of rephase modes in the rotation. */
        const val REPHASE_MODE_COUNT: Int = 5
    }
}
