package com.eignex.klause.backtrack

import com.eignex.klause.backtrack.selector.VarRef
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.Learned
import com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.LearnedConstraint
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.result.SearchEvent
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.solver.result.UnsatCore
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.MutableLongIntMap
import com.eignex.kumulant.math.splitmix64
import kotlin.random.Random

/**
 * A boundary the [DfsEngine] returns from [DfsEngine.runUntilEvent]. Both drivers (the satisfaction
 * sequence in `driveSearch` and the optimize stepper [ResumableMinimize]) map these to their own
 * result types. [L] is the driver-specific leaf payload a [SearchPolicy] surfaces.
 */
internal sealed interface EngineEvent<out L> {
    /** A feasible leaf the policy chose to surface (its [payload]). The engine has already recorded the
     *  blocking nogood and switched to backtracking, so the driver may resume immediately. */
    data class Solution<L>(val payload: L) : EngineEvent<L>

    /** The DFS tree is fully explored. [core] is the root-level (bake/seed) infeasibility core when the
     *  exhaustion was forced at the root, else null; [touched] is the seed-level projection set. */
    data class Exhausted(val core: UnsatCore?, val touched: IntArray) : EngineEvent<Nothing>

    /** The decision/instruction budget was hit inside a pin (`advance`). Always terminal. */
    data object BudgetCapped : EngineEvent<Nothing>

    /** A cancellation poll fired. The driver decides whether this is a resumable pause or a hard stop. */
    data object Cancelled : EngineEvent<Nothing>
}

/**
 * The per-search-mode seam over [DfsEngine]: everything that differs between the satisfaction path and
 * the branch-and-bound optimize path. The engine owns the shared machinery (descend / backtrack / the
 * backjump handler / restart housekeeping / seed / phase saving / touched-seed-levels / budget); the
 * policy fills the holes. [L] is the leaf payload the policy surfaces via [onLeaf] / [onFirstRun].
 *
 * Default implementations give the satisfaction behaviour (no LP, no incumbent); the optimize policy
 * overrides them.
 */
internal interface SearchPolicy<L> {
    /** LP node-pruning predicate handed to `advance`, or null for none. */
    val pruneIf: ((PropagationSession) -> Boolean)? get() = null

    /** LP immediate-backjump clause source handed to `advance`, or null for none. */
    val pruneLearned: (() -> Learned?)? get() = null

    /** Whether the current slice/run is cancelled (sat: the caller token; optimize: the slice token). */
    fun cancelled(): Boolean

    /** Advisory branch variable (optimize: the LP's reduced-cost pick), or null to defer to the selector. */
    fun branchPick(session: PropagationSession): VarRef? = null

    /** Reorder a node's candidate values (optimize: LP warm-start hints), or the values unchanged. */
    fun orderValues(varRef: VarRef, values: Sequence<Long>): Sequence<Long> = values

    /**
     * Handle a feasible leaf [snap] (the engine has already fired the selectors' `onSolution`, recorded
     * the pending blocking nogood, and set the engine to backtrack). Return the payload to surface as
     * [EngineEvent.Solution], or null to keep searching (optimize: a non-improving leaf).
     */
    fun onLeaf(snap: Sample): L?

    /** Assert the incumbent objective bound at the root; true iff that empties the root (optimum proven). */
    fun assertObjectiveBoundAtRoot(session: PropagationSession): Boolean = false

    /** Drain and register LP-learned nogoods at a restart; true iff a root contradiction proves exhaustion. */
    fun drainLpNogoodsAtRestart(session: PropagationSession): Boolean = false

    /** Restart hook fired right after `clauseExchange.onRestart` (optimize: cut exchange). */
    fun onRestartCuts(session: PropagationSession) {}

    /** Restart hook fired right after [assertObjectiveBoundAtRoot] (optimize: bound exchange). */
    fun onRestartBounds(session: PropagationSession) {}

    /** One-shot pre-search root work on the first [DfsEngine.runUntilEvent] (optimize: root LP + probes).
     *  May seed a first incumbent — return it to surface immediately, else null. */
    fun onFirstRun(): L? = null

    /** Fired on every [DfsEngine.runUntilEvent] entry after the first-run work (optimize: re-read the
     *  shared objective floor). */
    fun onResumeEntry() {}

    /** Fired right before the engine returns [EngineEvent.BudgetCapped] (sat: publish trailing glue). */
    fun onBudgetExit(session: PropagationSession) {}
}

/**
 * The single branch-and-bound orchestration in the backtrack solver. Holds the entire search state as
 * object fields (live [PropagationSession] with learned clauses + DFS trail, heuristics, phase saving,
 * budget) and advances to the next [EngineEvent] on each [runUntilEvent], so a caller can stream or pause
 * it. All per-mode behaviour is delegated to [policy]; see [SearchPolicy].
 *
 * The seed / bake root-infeasibility check runs at construction and is surfaced (once) as the first
 * [EngineEvent.Exhausted]; the driver maps it (satisfaction: an `Exhausted` outcome; optimize: an
 * `Infeasible` terminal).
 */
internal class DfsEngine<L>(
    private val solver: BacktrackSolver,
    params0: BacktrackParams,
    private val sink: SolveStatsSink?,
    private val policy: SearchPolicy<L>,
) {
    // One search owns unshared selectors. A BacktrackParams (and its stateful selectors) can be reused
    // across solves — every solve and minimize funnels through a DfsEngine, so copy here, the one place
    // both drivers build the search, rather than let a prior problem's per-search state leak in.
    private val params: BacktrackParams = params0.copy(
        variableSelector = params0.variableSelector.fresh(),
        valueSelector = params0.valueSelector.fresh(),
    )
    private val problem = solver.problem

    // null = auto-dispatch: the native-SAT lane engages exactly when the baked problem is eligible
    // (PropagationState gates on that anyway). An explicit true/false overrides the auto choice.
    private val session = PropagationSession(
        problem,
        params.cancellation,
        params.propagationCancelFloor,
        nativeSat = params.nativeSat ?: true,
        pbLearning = params.pbLearning,
    )

    // Number of decision levels the seed uses (bool pins then int pins); levels 1..numSeed are
    // assumptions, levels > numSeed are post-seed DFS decisions.
    // var: a repair driver [reseed]s the engine on successive pin sets (LNS), each a fresh seed.
    private var numSeed = params.assumptions.boolKeys.size + params.assumptions.intKeys.size
    private var touchedSeedLevels = if (numSeed > 0) IntHashSet() else null

    private val phase = PhaseSaving(problem.numBoolVars, problem.numIntVars, params)
    private val onConflictTick: () -> Unit = phase::onConflictTick

    private val baseSeed: Long = params.randomSeed ?: Random.Default.nextLong()
    private val rng = Random(baseSeed)
    private var decisionsLeft = minOf(params.maxDecisions, params.maxInstructions ?: Long.MAX_VALUE)

    private val relearnCounts = MutableLongIntMap()
    private val relearnTripped: (LearnedConstraint) -> Boolean = { learned ->
        var h = 0L
        for (lit in learned.guardLiterals) h += splitmix64(lit.toLong())
        val n = relearnCounts.addTo(h, 1)
        if (n > 1) sink?.search?.observeRelearn()
        n > RELEARN_FALLBACK_THRESHOLD
    }

    private val restart = RestartSchedule.from(params)
    private var restartCount = 0L

    // Only schedules that couple the phase regime to their mode report a non-UNMANAGED phase; capture
    // it once so the common path pays nothing and its phasing stays byte-identical.
    private val phaseManaged = restart.phaseMode() != PhaseMode.UNMANAGED
    private val vivifyEnabled = params.vivification && params.assumptions.isEmpty
    private var vivifyCursor = 0
    private val poller = DeadlinePoller()

    private val trail: MutableList<TrailNode> = ArrayList()
    private var pendingBlock: Sample? = null

    // Best pooled solution already adopted as phase hints; identity-gates redundant re-imports.
    private var lastPooledSolution: Sample? = null
    private var descend = true
    private var decisionsThisRun = 0L
    private var runActive = false
    private var started = false

    /** The live session — exposed so the optimize policy can read/imply on it (root LP, shaving). */
    val propagationSession: PropagationSession get() = session

    /** Root-infeasibility event decided at construction (bake / seed unsat), surfaced once; null if the
     *  root is feasible. */
    private var rootExhausted: EngineEvent.Exhausted? = null

    init {
        val baked = problem.baked
        if (baked is PropagationResult.Unsat) {
            rootExhausted = EngineEvent.Exhausted(solver.coreOf(baked), solver.touchedToArray(touchedSeedLevels))
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
                recordTouchedSeedLevels(seedResult.conflictLevels)
                rootExhausted =
                    EngineEvent.Exhausted(solver.coreOf(seedResult), solver.touchedToArray(touchedSeedLevels))
            } else {
                // Import nogoods already in the shared pool before the first run (cross-arm, #381); the
                // session persists for the whole search so this arm's own clauses are never lost.
                params.clauseExchange?.onSearchStart(session)
            }
        }
    }

    /**
     * Re-drive this engine on a new [assumptions] set with a fresh [decisionBudget], reusing the
     * persistent [session] (its learned-clause database and baked root domains), the [PhaseSaving] warm
     * start, and the heuristics — the seam the LNS destroy/repair loop uses to solve one pinned fragment
     * after another without rebuilding the session or its LP relaxation. Resets the per-run search state
     * to root; everything the session holds survives.
     *
     * Soundness requirement on the caller: drive successive repairs against a **monotone non-increasing**
     * objective cutoff. The reused session keeps the permanent objective-bound clauses across reseeds, so
     * a monotone cutoff makes every stale bound only looser than the current one — it can never wrongly
     * prune. (Blocking nogoods likewise only forbid already-surfaced assignments.)
     */
    fun reseed(assumptions: Assumptions, decisionBudget: Long) {
        trail.clear()
        pendingBlock = null
        descend = true
        decisionsThisRun = 0
        runActive = false
        started = false
        restartCount = 0
        decisionsLeft = decisionBudget
        vivifyCursor = 0
        rootExhausted = null
        numSeed = assumptions.boolKeys.size + assumptions.intKeys.size
        touchedSeedLevels = if (numSeed > 0) IntHashSet() else null
        // Diff against the pins the previous fragment left standing rather than clearing to root: the
        // shared complement carries over, so re-seeding a small-delta fragment stays cheap (#644).
        val seedResult = session.reseedFrom(assumptions)
        if (seedResult is PropagationResult.Unsat) {
            recordTouchedSeedLevels(seedResult.conflictLevels)
            rootExhausted =
                EngineEvent.Exhausted(solver.coreOf(seedResult), solver.touchedToArray(touchedSeedLevels))
        } else {
            params.clauseExchange?.onSearchStart(session)
        }
    }

    private fun recordTouchedSeedLevels(levels: IntArray) {
        val touched = touchedSeedLevels ?: return
        for (l in levels) if (l in 1..numSeed) touched.add(l)
    }

    private fun exhausted(): EngineEvent.Exhausted =
        EngineEvent.Exhausted(core = null, touched = solver.touchedToArray(touchedSeedLevels))

    /** Advance the search to the next [EngineEvent], retaining all state so the next call resumes. */
    fun runUntilEvent(): EngineEvent<L> {
        rootExhausted?.let { return it }
        // The bake / seed fixpoint (run at construction) can itself be cut short by the deadline on
        // a slow propagator over wide domains; surface that as a budget cap before any descent.
        if (session.fixpointCancelled) return EngineEvent.BudgetCapped
        if (!started) {
            started = true
            policy.onFirstRun()?.let { return EngineEvent.Solution(it) }
        }
        policy.onResumeEntry()
        outer@ while (true) {
            if (!runActive) {
                restart.beginRun()
                decisionsThisRun = 0
                descend = true
                runActive = true
            }
            while (true) {
                if (poller.due()) {
                    if (policy.cancelled()) {
                        // Publish trailing glue clauses so the next segment (this arm or a sibling)
                        // imports them at its start (#381), then hand the cancellation to the driver.
                        params.clauseExchange?.onSearchEnd(session)
                        return EngineEvent.Cancelled
                    }
                    poller.rearm()
                }
                if (restart.shouldRestart(decisionsThisRun)) {
                    doRestart()?.let { return it }
                    runActive = false
                    continue@outer
                }
                val event = if (descend) descendStep() else backtrackStep()
                if (event != null) return event
            }
        }
    }

    /** One descent step: pick+branch a variable (or record a leaf), advance, handle the outcome. */
    private fun descendStep(): EngineEvent<L>? {
        val varRef = policy.branchPick(session) ?: params.variableSelector.pick(session, rng)
        if (varRef == null) {
            // A seed fixpoint the deadline cut short can leave a full assignment with a pending,
            // unpropagated (possibly violated) factor. Never emit that as a solution.
            if (session.fixpointCancelled) {
                policy.onBudgetExit(session)
                descend = false
                return EngineEvent.BudgetCapped
            }
            val snap = snapshotAssignment(session)
            params.variableSelector.onSolution(snap)
            params.valueSelector.onSolution(snap)
            phase.onSolution(snap)
            restart.onSolution()
            pendingBlock = snap
            descend = false
            return policy.onLeaf(snap)?.let { EngineEvent.Solution(it) }
        }
        val values = params.valueSelector.values(session, varRef, rng)
        if (phaseManaged) phase.setManagedMode(restart.phaseMode())
        val phased = phase.applyPhase(varRef, values, rng)
        val ordered = policy.orderValues(varRef, phased)
        val node = solver.makeNode(varRef, ordered)
        val out = runAdvance(node)
        return when (out) {
            AdvanceOutcome.Success -> {
                phase.capture(varRef, session)
                trail.add(node)
                sink?.search?.observeNode(trail.size)
                phase.captureTargetIfDeeper(session, trail.size)
                null
            }

            AdvanceOutcome.Exhausted -> {
                descend = false
                null
            }

            AdvanceOutcome.BudgetCapped -> {
                policy.onBudgetExit(session)
                EngineEvent.BudgetCapped
            }

            is AdvanceOutcome.Backjump -> handleBackjump(out.learned, alignFirst = false)
        }
    }

    /** One backtrack step: replay a pending root block, or pop the trail top and re-advance it. */
    private fun backtrackStep(): EngineEvent<L>? {
        val rootBlock = pendingBlock
        if (rootBlock != null) {
            // Apply the pending blocking nogood at the root, where it can neither conflict nor assert
            // mid-trail; a root contradiction proves the remaining space empty.
            pendingBlock = null
            popTrailToRoot()
            val nogood = session.assignmentNogood(rootBlock.bools, rootBlock.ints)
            if (nogood.isNotEmpty()) {
                val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                if (res is PropagationResult.Unsat) return exhausted()
            }
            if (policy.assertObjectiveBoundAtRoot(session)) return exhausted()
            descend = true
            return null
        }
        if (trail.isEmpty()) return exhausted()
        val top = trail.last()
        session.popLast()
        val out = runAdvance(top)
        return when (out) {
            AdvanceOutcome.Success -> {
                phase.capture(top.varRef, session)
                descend = true
                null
            }

            AdvanceOutcome.Exhausted -> {
                trail.removeAt(trail.size - 1)
                null
            }

            AdvanceOutcome.BudgetCapped -> {
                policy.onBudgetExit(session)
                EngineEvent.BudgetCapped
            }

            is AdvanceOutcome.Backjump -> handleBackjump(out.learned, alignFirst = true)
        }
    }

    /** The shared backjump handler: record touched seed levels, feed the restart policy, execute the
     *  backjump+learn, and translate its terminal to an engine event (or null to continue). */
    private fun handleBackjump(learned: LearnedConstraint, alignFirst: Boolean): EngineEvent<L>? {
        recordTouchedSeedLevels(learned.decisionLevels)
        restart.recordConflict(learned.lbd, trail.size)
        return when (solver.backjumpAndLearn(learned, trail, session, params, alignFirst)) {
            BackjumpTerm.Resume -> {
                descend = true
                null
            }

            BackjumpTerm.Exhausted -> exhausted()

            BackjumpTerm.Stuck -> {
                descend = false
                null
            }
        }
    }

    /** Restart housekeeping. Returns a terminal [EngineEvent.Exhausted] when a root contradiction proves
     *  exhaustion, else null. The side-effect order matches the pre-unification loops on both paths; the
     *  optimize-only cut/bound exchanges ride the [SearchPolicy.onRestartCuts] / [SearchPolicy.onRestartBounds]
     *  hooks placed exactly where they sat before. */
    private fun doRestart(): EngineEvent<L>? {
        popTrailToRoot()
        val restartBlock = pendingBlock
        if (restartBlock != null) {
            pendingBlock = null
            if (restartBlock.bools.isNotEmpty() || restartBlock.ints.isNotEmpty()) {
                val nogood = session.assignmentNogood(restartBlock.bools, restartBlock.ints)
                val res = session.addLearnedClause(Clause(nogood), lbd = nogood.size, permanent = true)
                if (res is PropagationResult.Unsat) return exhausted()
            }
        }
        if (policy.drainLpNogoodsAtRestart(session)) return exhausted()
        params.clauseExchange?.onRestart(session)
        policy.onRestartCuts(session)
        if (policy.assertObjectiveBoundAtRoot(session)) return exhausted()
        policy.onRestartBounds(session)
        params.variableSelector.onRestart()
        params.valueSelector.onRestart()
        solver.forgetIfOverCap(session, params)
        if (vivifyEnabled) vivifyCursor = solver.vivify(session, params, vivifyCursor)
        restart.onRestart()
        importPooledSolution()
        val restartIndex = ++restartCount
        sink?.search?.observeRestart()
        params.onEvent?.invoke(SearchEvent.Restart(restartIndex, decisionsThisRun))
        return null
    }

    /**
     * Re-phase toward the best assignment any arm has published (#644 collaboration): if the shared pool
     * now holds a solution this engine has not yet imported, adopt it as solution-phasing hints so the
     * stable phase dives toward the global incumbent rather than only this arm's own. Identity-gated so an
     * unchanged pool costs nothing. Pure heuristic — [PhaseSaving.onSolution]/[PhaseSaving.applyPhase] only
     * reorder value trials (clamping out-of-domain integers), so a foreign assignment cannot make the
     * search unsound; a no-op unless [BacktrackParams.solutionPhasing] is on.
     */
    private fun importPooledSolution() {
        val pooled = params.pooledSolutionSupplier?.invoke() ?: return
        if (pooled === lastPooledSolution) return
        lastPooledSolution = pooled
        phase.onSolution(pooled)
    }

    /** Pop every decision frame, reverting the session in lockstep, back to the post-seed root. */
    private fun popTrailToRoot() {
        while (trail.isNotEmpty()) {
            session.popLast()
            trail.removeAt(trail.size - 1)
        }
    }

    /** One pin attempt against the session, wiring the budget probe/decrement and conflict callbacks. */
    private fun runAdvance(node: TrailNode): AdvanceOutcome {
        val decsBefore = decisionsLeft
        val out = solver.advance(
            node,
            session,
            params,
            policy.pruneIf,
            { decisionsLeft },
            { decisionsLeft-- },
            sink,
            relearnTripped,
            onConflictTick,
            policy.pruneLearned,
        )
        decisionsThisRun += decsBefore - decisionsLeft
        return out
    }
}
