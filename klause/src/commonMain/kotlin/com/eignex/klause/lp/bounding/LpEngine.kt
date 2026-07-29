package com.eignex.klause.lp.bounding

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.lp.Basis
import com.eignex.klause.lp.LpVerdict
import com.eignex.klause.lp.RevisedSimplex
import com.eignex.klause.lp.bound.CumulativeEnergeticBound
import com.eignex.klause.lp.bound.CumulativeFlowBound
import com.eignex.klause.lp.bound.KnapsackLagrangianBound
import com.eignex.klause.lp.bound.LagrangianBound
import com.eignex.klause.lp.bound.LagrangianDualBound
import com.eignex.klause.lp.bound.SchedulingFeasibilityBound
import com.eignex.klause.lp.cut.AggregationMirSeparator
import com.eignex.klause.lp.cut.AllDifferentSeparator
import com.eignex.klause.lp.cut.AssignmentObjectiveCut
import com.eignex.klause.lp.cut.CircuitSeparator
import com.eignex.klause.lp.cut.CliqueCutSeparator
import com.eignex.klause.lp.cut.Cut
import com.eignex.klause.lp.cut.CutExchange
import com.eignex.klause.lp.cut.CutPool
import com.eignex.klause.lp.cut.CutSeparator
import com.eignex.klause.lp.cut.CutSharing
import com.eignex.klause.lp.cut.FlowCoverSeparator
import com.eignex.klause.lp.cut.GccSeparator
import com.eignex.klause.lp.cut.ImpliedBoundSeparator
import com.eignex.klause.lp.cut.KnapsackCoverSeparator
import com.eignex.klause.lp.cut.SharedCut
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.LeafRealResult
import com.eignex.klause.lp.relaxation.LpExplanation
import com.eignex.klause.lp.relaxation.LpRelaxation
import com.eignex.klause.lp.relaxation.gatedEnforcement
import com.eignex.klause.lp.relaxation.rebound
import com.eignex.klause.lp.solveAndCertify
import com.eignex.klause.propagation.ConflictAnalyzer.AnalysisResult.Learned
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SolveStatsSink
import com.eignex.klause.util.EmptyDoubleArray
import com.eignex.klause.util.EmptyIntArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import kotlin.time.TimeSource

/** The persistent gated-residual float filter: the node-invariant [relaxation], the ONE [simplex]
 *  instance re-solving it with its kept factorization, and the reusable per-row [enforced] buffer
 *  [LpEngine.gatedResidual] refreshes to the live pins each node. [lastEnforced]/[lastFeasible] memo
 *  the previous solved node: the model is a pure function of the enforcement, so an unchanged
 *  fingerprint after a feasible solve (a decision that flips no real atom) needs no solve at all. */
internal class GatedResidual(val relaxation: LpRelaxation, val simplex: RevisedSimplex, val enforced: BooleanArray) {
    val lastEnforced: BooleanArray = BooleanArray(enforced.size)
    var lastFeasible: Boolean = false
}

/**
 * Per-node relaxation-bounding runtime for branch-and-bound. Owns the whole LP-relaxation family
 * state — the [CpToLpRelaxation] relaxer, structural cut separators and the harvested global-cut
 * pool, the Lagrangian / knapsack-Lagrangian / energetic / cumulative-flow bounds with their
 * multiplier vectors and check counters, the adaptive auto-off gate, the per-depth warm-start basis
 * cache, LP branching hints and the pending LP-learned nogood pool — all derived from [problem],
 * [objective] and [params].
 *
 * [pruneNode] is the per-node cascade (lower bound → scheduling-feasibility bounds → Lagrangian
 * bounds → LP relaxation): it returns true when the node is provably dominated/infeasible, with the
 * same ordering and side effects ([sink] telemetry, nogood collection, multiplier updates, warm-start
 * basis caching, [lastBackjump]) the search engine relied on inline. Internal fields are null/empty
 * when their feature flag is off, so the cascade simply skips those arms.
 */
internal class LpEngine(
    val problem: Problem,
    private val objective: LinearObjective,
    params0: LpParams,
    private val sink: SolveStatsSink,
) {
    /** The relaxation-bound family resolved from the high-level emphasis ([LpParams.lpConfig])
     *  against this problem's structure (#429); with no emphasis set, the explicit
     *  [LpParams.lpPlan] is used verbatim. Resolving here makes the engine the single home for
     *  the intent→plan step, so callers carry only the intent and never a separately-resolved copy. */
    val params: LpParams =
        params0.lpConfig?.let { params0.copy(lpPlan = LpAutoConfig.resolve(problem, it, params0.lpPlan)) } ?: params0

    /** Construct the relaxer for [plan]'s hull flags, or null when bounding is off. Factored so the
     *  ineffective-hull probe can build variants with whole families ([plan]) or individual factor
     *  hulls ([suppressedHullFactors]) turned off. */
    private fun buildRelaxer(plan: LpPlan, suppressedHullFactors: Set<Int> = emptySet()): CpToLpRelaxation? =
        if (plan.bounding) {
            CpToLpRelaxation(
                problem,
                objective,
                elementHull = plan.element,
                tableHull = plan.table,
                cumulative = plan.cumulative,
                diffn = plan.diffn,
                cumulativeTimeIndexed = plan.cumulativeTimeIndexed,
                nValueHull = plan.nValue,
                regularHull = plan.regular,
                mddHull = plan.mdd,
                gccCountHull = plan.gccCount,
                circuitArcs = plan.circuit,
                objectiveCone = plan.objectiveCone,
                realResidual = plan.realResidual,
                linMaxTightFace = plan.linMaxTightFace,
                productMcCormick = plan.productMcCormick,
                booleanRlt = plan.booleanRlt,
                suppressedHullFactors = suppressedHullFactors,
            )
        } else {
            null
        }

    /** The active relaxer. Rebuilt once by [pruneIneffectiveHulls] when a hull is dropped; otherwise the
     *  [params]-resolved one for the whole search. */
    var lpRelaxer = buildRelaxer(params.lpPlan)
        private set

    // Structure-based cut separators (#22/#705) run on the sparse LP point; circuit cuts are deferred
    // until the arc model is rebuilt on the sparse relaxation.
    val lpSeparators: List<CutSeparator> = if (params.lpPlan.cuts || params.lpPlan.circuit) {
        buildList {
            if (params.lpPlan.cuts) {
                add(AllDifferentSeparator())
                add(GccSeparator())
                add(KnapsackCoverSeparator())
                add(CliqueCutSeparator())
                add(AggregationMirSeparator())
                if (params.lpPlan.impliedBoundCuts) add(ImpliedBoundSeparator())
                if (params.lpPlan.flowCoverCuts) add(FlowCoverSeparator())
                val coef = LongArray(problem.numIntVars) { objective.intCoefficients.getOrElse(it) { 0L } }
                add(AssignmentObjectiveCut(coef))
            }
            if (params.lpPlan.circuit) add(CircuitSeparator())
        }
    } else {
        emptyList()
    }

    // Persistent pool of global cuts (#22/#40): seeded from the root harvest in [initRootLp] and grown
    // by during-search separation (#41). Every cut is global, so the pool is sound at every node.
    val cutPool = CutPool()

    /** The global cuts folded into every node's relaxation — the live contents of [cutPool]. */
    val lpGlobalCuts: List<Cut> get() = cutPool.cuts()

    /**
     * Exchange this engine's global cuts with a portfolio peer via [exchange] (#809): import the cuts
     * other arms published — re-mapped onto this engine's stable column layout — and export this
     * engine's own. A no-op until a persistent relaxation exists, since cut sharing rides its
     * fixed column→variable maps; a non-persistent relaxation has no single layout to map through.
     * Importing only adds globally-valid cuts, so it is sound at any node; the next node folds the
     * violated subset in via [CutPool.select], so no base invalidation is needed (mirroring
     * [recordSearchCuts]).
     */
    internal fun exchangeCuts(exchange: CutExchange) {
        val relaxation = persistentRelaxation ?: return
        exchange.exchange(
            object : CutSharing {
                override fun exportGlobalCuts(): List<SharedCut> =
                    cutPool.cuts().mapNotNull { if (it.global) SharedCut.fromCut(it, relaxation) else null }

                override fun importCuts(cuts: List<SharedCut>) {
                    for (c in cuts) c.toCut(relaxation)?.let { cutPool.add(it) }
                }
            },
        )
    }

    /**
     * Persist the globally-valid members of [cuts] into the [cutPool] (#41); node-local cuts are
     * ignored here (the caller uses them transiently). The pool is trimmed to its cap by activity at
     * [primal]. The cut-free persistent base is untouched — every node folds the violated subset via
     * [CutPool.select]. Sound: every persisted cut is global, valid at every solution.
     */
    fun recordSearchCuts(cuts: List<Cut>, primal: DoubleArray) {
        var added = 0
        for (c in cuts) if (c.global && cutPool.add(c)) added++
        if (added == 0) return
        if (cutPool.size > cutPool.maxCuts) cutPool.retainMostActive(primal)
    }
    private val lagBound = if (params.lpPlan.lagrangian) {
        LagrangianBound(problem, objective).takeIf { it.applicable }
    } else {
        null
    }
    private val knapsackLagBound = if (params.lpPlan.knapsackLagrangian) {
        KnapsackLagrangianBound(problem, objective).takeIf { it.applicable }
    } else {
        null
    }
    private val energeticBound = if (params.lpPlan.energeticReasoning) {
        CumulativeEnergeticBound(problem).takeIf { it.applicable }
    } else {
        null
    }
    private val cumulativeFlowBound = if (params.lpPlan.cumulativeFlow) {
        CumulativeFlowBound(problem).takeIf { it.applicable }
    } else {
        null
    }

    /** The single objective variable's modular residue, when it is defined by a linear equality
     *  `a·v + Σ cⱼ·xⱼ = b` with `|a| = 1` and `g = gcd(cⱼ) > 1`: then `v ≡ r (mod g)` in every solution
     *  (`r = (a·b) mod g`), recorded as `(v, g, r)`. An LP lower bound on `v` can be rounded up to the
     *  next value congruent to `r` — a tighter sound cutoff. `null` when no defining equality gives one. */
    internal val objectiveModulus: Triple<Int, Long, Long>? by lazy { computeObjectiveModulus() }

    private fun computeObjectiveModulus(): Triple<Int, Long, Long>? {
        val v = objective.singleIntObjective()?.varId ?: return null
        var best: Triple<Int, Long, Long>? = null
        for (f in problem.factors) {
            if (f !is Linear || f.hasReals || f.op != LinearOp.EQ) continue
            val vi = f.vars.indexOf(v)
            if (vi < 0) continue
            val a = f.coeffs[vi]
            if (a != 1L && a != -1L) continue
            var g = 0L
            for (j in f.vars.indices) if (j != vi) g = gcdOfLong(g, f.coeffs[j])
            if (g <= 1L) continue
            // a·v ≡ b (mod g); a = ±1 ⇒ v ≡ a·b (mod g). Keep the largest modulus (tightest rounding).
            if (best != null && g <= best.second) continue
            val residue = (a * f.bound).mod(g)
            best = Triple(v, g, residue)
        }
        return best
    }

    private fun gcdOfLong(a: Long, b: Long): Long {
        var x = if (a < 0) -a else a
        var y = if (b < 0) -b else b
        while (y != 0L) {
            val t = x % y
            x = y
            y = t
        }
        return x
    }
    private var lpCheckCounter = 0

    // Wall-clock LP circuit breaker: the count-based [lpLadder] below needs a warmup window of
    // solves to demote, so it can never shed an LP whose single solve is seconds — that LP just burns the
    // whole budget bounding nothing (elitserien/cyclic-rcpsp: lpMs≈budget, prunes=0, too few solves to
    // warm up). When the total solve budget is known, the LP (one-shot root work charged via
    // [chargeRootLpWall] + the per-node solves timed in the bound) may spend at most
    // `min(fraction × budget, cap)` of it before search; if it hits that while still under the ladder's
    // warmup and not having pruned, per-node LP is disabled and the arm runs as a bare combinatorial
    // search. A cheap LP reaches the warmup first and is left to the ladder. See [LpWallBreaker].
    private val lpWallBreaker = LpWallBreaker(
        budgetMillis = params.solveBudgetMillis?.takeIf { params.lpPlan.lpWallBudgetFraction > 0.0 }
            ?.let { minOf((it * params.lpPlan.lpWallBudgetFraction).toLong(), params.lpPlan.lpWallBudgetMillis) } ?: 0L,
        warmupSolves = LpEffortLadder.DEFAULT_WARMUP,
    )

    /** Milliseconds of LP wall budget left, or `null` when no budget is set (breaker off). The
     *  one-shot root work is time-boxed to this so it competes with the per-node solves for one budget,
     *  rather than the looser [LpPlan.rootBudgetFraction] cap alone letting it consume the whole slice. */
    fun lpWallRemainingMillis(): Long? = lpWallBreaker.remainingMillis()

    /** Charge the one-shot pre-search root LP work's wall time against the shared LP wall budget,
     *  so root and per-node solves compete for the same fraction of the deadline. Called once, after the
     *  root work, by [com.eignex.klause.backtrack.ResumableMinimize]. Root work never counts as a prune. */
    fun chargeRootLpWall(millis: Long) = lpWallBreaker.charge(millis, pruned = false)

    // Adaptive LP effort ladder (#32, generalizing the #614 auto-off): the emphasis sets the ceiling
    // rung (cuts when enabled, else the bare bound), and a rolling prune-rate window descends one rung
    // at a time — shedding during-search cuts before the bound — re-probing upward on backoff. Sound:
    // every rung is a valid bound / off, so the gate only changes work, never solutions.
    private val lpLadder = LpEffortLadder(
        top = LpEffort.ceiling(cutsPermitted = params.lpPlan.cuts),
        reprobeBase = if (params.lpPlan.autoOffReprobe) LpEffortLadder.DEFAULT_REPROBE_BASE else Int.MAX_VALUE,
    )

    // Per-separator activity gate (#59): disables a single unproductive cut family while the others keep
    // separating — the per-technique complement of the whole-rung [lpLadder]. Sound: skipping a separator
    // only forgoes its cuts, never a solution.
    val lpSeparatorGate = LpSeparatorGate(
        count = lpSeparators.size,
        reprobeBase = if (params.lpPlan.autoOffReprobe) LpEffortLadder.DEFAULT_REPROBE_BASE else Int.MAX_VALUE,
    )
    val lpNogoods: LpNogoodPool? = if (params.lpPlan.learn) LpNogoodPool() else null
    private val lpBasisByDepth = ArrayList<Basis?>()

    /** LP-solution record sink for branching hints (set by the search when LP branching is on; null in
     *  presolve). The engine only records into it; the concrete `LpHints` and its selection reads live in
     *  the search layer. */
    var lpHints: LpHintSink? = null
    private var lpBackjump: Learned? = null

    // Persistent global LP (#39): for a node-invariant relaxation (no auxiliary columns, no live-M
    // rows) the per-node delta is column bounds only, so the relaxation is built once from the declared
    // domains and re-bound at each node instead of rebuilt. The base is cut-free — global cuts are folded
    // per node by [LpBounding] via [CutPool.select], so the pool can grow without invalidating the base.
    private var persistentResolved = false
    private var persistentRelaxation: LpRelaxation? = null

    /**
     * The **cut-free** LP relaxation for the current node (#39): the persistent relaxation re-bound to
     * [session]'s live column bounds when eligible, else a fresh per-node build. On first call it builds a
     * base relaxation from the declared domains; if that base is [LpRelaxation.persistentEligible] it is
     * cached and every node re-binds it — bit-identical to a rebuild for eligible models, but skipping the
     * matrix reconstruction. The harvested global cuts are not baked in here: the bound path folds the
     * subset its LP point actually violates via [CutPool.select] (#40 / D8), so the base stays
     * node-invariant and the per-node cut count is bounded by efficacy rather than the whole pool.
     */
    internal fun nodeRelaxation(relaxer: CpToLpRelaxation, session: PropagationSession): LpRelaxation {
        if (!persistentResolved) {
            persistentResolved = true
            val base = relaxer.build(PropagationSession(problem))
            if (base.persistentEligible) persistentRelaxation = base
        }
        persistentRelaxation?.let { return it.rebound(session) }
        // Residual real models rebuild only when an activating pin changed: with no integer columns
        // every row and bound is a function of the aux-bool pin set alone, so an unchanged fingerprint
        // means the previously built relaxation is byte-identical — the common case along a dive,
        // where most decisions touch Booleans that activate no real row.
        if (residualAuxVars.isNotEmpty() && problem.numIntVars == 0) {
            val key = IntArray(residualAuxVars.size) {
                when (session.boolValue(residualAuxVars[it])) {
                    null -> 2
                    true -> 1
                    false -> 0
                }
            }
            residualCache?.let { cached ->
                if (residualCacheKey?.contentEquals(key) == true) return cached
            }
            val built = relaxer.build(session)
            residualCache = built
            residualCacheKey = key
            return built
        }
        return relaxer.build(session)
    }

    // Pin-fingerprint cache for the residual real relaxation (realResidual plans, no integer columns).
    private var residualCacheKey: IntArray? = null
    private var residualCache: LpRelaxation? = null

    // Gated residual float filter (pure-real models): a structurally node-invariant relaxation whose
    // rows toggle by per-row enforcement alone, plus ONE simplex instance that re-solves it with its
    // kept LU factorization — the per-node build+factorize that dominated the satisfaction path
    // collapses to a few dual pivots. Exact certificates never read this model; they run on the
    // per-node pin-consulting build as before.
    private var gatedResolved = false
    private var gatedFilter: GatedResidual? = null

    /** The gated residual filter with its enforcement refreshed to [session]'s pins, or null when the
     *  model does not qualify (not pure-real, oversized, or no gated rows). */
    internal fun gatedResidual(session: PropagationSession): GatedResidual? {
        if (!params.lpPlan.realResidual || residualOversized) return null
        if (!gatedResolved) {
            gatedResolved = true
            val built = lpRelaxer?.buildGatedResidual()
            if (built != null && built.gatedRows.isNotEmpty() && built.model.n > 0) {
                gatedFilter = GatedResidual(
                    built,
                    // A long eta chain: the persistent instance's pivots accumulate ACROSS nodes
                    // (reconciliation + dual repair after each pin flip), so the default limit would
                    // refactorize the full basis every few nodes — the exact cost this filter removes.
                    RevisedSimplex(built.model, params.cancellation, refactorEtaLimit = GATED_ETA_LIMIT),
                    BooleanArray(built.model.m),
                )
            }
        }
        val filter = gatedFilter ?: return null
        filter.relaxation.gatedEnforcement(session, filter.enforced)
        return filter
    }
    private val residualAuxVars: IntArray = if (params0.lpPlan.realResidual) {
        problem.factors.filterIsInstance<ReifiedRealLinear>().map { it.aux }.distinct().sorted().toIntArray()
    } else {
        EmptyIntArray
    }

    /** Whether the residual real model is too large for per-node / per-leaf exact work: one sparse-LU
     *  factorization at this row count outruns any node budget and its fill explodes memory, so the
     *  checks decline and the verdict honestly degrades to unknown instead of hanging. */
    val residualOversized: Boolean = params0.lpPlan.realResidual &&
        problem.factors.count { it is ReifiedRealLinear || (it is Linear && it.hasReals) } > RESIDUAL_MAX_ROWS

    /** The asserting LP backjump clause derived during the last [pruneNode] (#280), or null. */
    fun lastBackjump(): Learned? = lpBackjump

    /**
     * Exact residual verdict at a full-assignment leaf, over the relaxation built from the live
     * [session] — the strict-aware decider ([solveAndCertify]'s delta-rational fallback) with the
     * rows premise-cited, so an [LpVerdict.INFEASIBLE] leaf also derives a theory lemma over the
     * activating literals (a Farkas-ray clause when the certificate carries an integer ray, else the
     * active rows' premises) and stashes it for [lastBackjump]. On [LpVerdict.OPTIMAL] the returned
     * reals complete the assignment into a full solution.
     */
    fun leafCertify(session: PropagationSession): LeafRealResult {
        lpBackjump = null
        if (residualOversized) return LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
        val relaxer = lpRelaxer ?: return LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
        val relaxation = nodeRelaxation(relaxer, session)
        val model = relaxation.model
        if (model.n == 0) return LeafRealResult(LpVerdict.OPTIMAL, DoubleArray(problem.numRealVars))
        val certified = solveAndCertify(model, cancellation = params.cancellation)
        return when (certified.verdict) {
            LpVerdict.OPTIMAL -> {
                val primal = certified.exactPrimal ?: certified.float?.primal
                    ?: return LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
                val reals = DoubleArray(problem.numRealVars)
                for (col in relaxation.colRealId.indices) {
                    val r = relaxation.colRealId[col]
                    if (r >= 0 && col < primal.size) reals[r] += relaxation.colRealSign[col] * primal[col]
                }
                LeafRealResult(LpVerdict.OPTIMAL, reals)
            }

            LpVerdict.INFEASIBLE -> {
                // Pool the theory lemma for restart-time registration: the leaf itself just rejects
                // (the engine backtracks chronologically), and the next restart makes the lemma a
                // permanent clause pruning the dead region everywhere.
                val clause = certified.farkasRay
                    ?.let { LpExplanation.infeasibilityClause(relaxation, it, session) }
                    ?: certified.infeasibleRows?.let { LpExplanation.premiseClauseForRows(relaxation, it, session) }
                    ?: activePremiseClause(relaxation, session)
                if (clause != null) lpNogoods?.add(clause)
                LeafRealResult(LpVerdict.INFEASIBLE, EmptyDoubleArray)
            }

            LpVerdict.INDETERMINATE -> LeafRealResult(LpVerdict.INDETERMINATE, EmptyDoubleArray)
        }
    }

    /** The active rows' premises as one clause — the whole activating-literal set is real-infeasible.
     *  Weaker than a ray-filtered clause but still a valid theory lemma; null when some non-global row
     *  has no recorded premise (inexpressible) or nothing is cited. */
    private fun activePremiseClause(relaxation: LpRelaxation, session: PropagationSession): IntArray? {
        val lits = IntArrayList()
        val seen = IntHashSet()
        val rows = IntArray(relaxation.model.m) { it }
        val ok = LpExplanation.addRowPremiseLits(lits, seen, relaxation, rows, session)
        return if (ok && lits.size > 0) lits.toIntArray() else null
    }

    /**
     * Per-node prune cascade. Returns true when this node is provably dominated by the incumbent
     * ([effectiveBound]) or infeasible. The cascade order and every side effect (check-counter
     * increments, [sink] telemetry, [lpNogoods] collection, multiplier reassignments, warm-start
     * basis caching and the [lastBackjump] assignment) are preserved exactly.
     */
    fun pruneNode(
        session: PropagationSession,
        effectiveBound: Double,
        objectiveVar: Int,
        objectiveAscending: Boolean,
    ): Boolean {
        lpBackjump = null
        for (b in bounds) {
            if (b.applicable && b.prune(session, effectiveBound, objectiveVar, objectiveAscending)) return true
        }
        return false
    }

    /** Lower-bound dominance against the incumbent — the cheapest, always-applicable arm. */
    private inner class LinearBound : RelaxationBound {
        override val applicable: Boolean get() = true

        override fun prune(
            session: PropagationSession,
            effectiveBound: Double,
            objectiveVar: Int,
            objectiveAscending: Boolean,
        ): Boolean = linearLowerBound(objective, session) >= effectiveBound
    }

    /** Scheduling-feasibility prune arm (energetic #562, cumulative-flow — same prune family): every
     *  [checkEvery] visits, prune the node when [bound] proves the schedule infeasible, recording the
     *  explanation as an LP nogood. One class drives both bounds. */
    private inner class SchedulingFeasibilityArm(
        private val bound: SchedulingFeasibilityBound,
        private val checkEvery: Int,
    ) : RelaxationBound {
        private var checkCounter = 0
        override val applicable: Boolean get() = true

        override fun prune(
            session: PropagationSession,
            effectiveBound: Double,
            objectiveVar: Int,
            objectiveAscending: Boolean,
        ): Boolean {
            if (++checkCounter % checkEvery != 0 || !bound.isInfeasible(session)) return false
            sink.scheduling.observeEnergeticPrune()
            lpNogoods?.let { pool -> bound.explain(session)?.let { pool.add(it) } }
            return true
        }
    }

    /** Lagrangian dual prune arm (#429, and knapsack-Lagrangian): reassigns its own persistent
     *  multiplier vector each call and prunes when the dual bound beats the incumbent. One class
     *  drives both bounds. */
    private inner class LagrangianArm(private val bound: LagrangianDualBound) : RelaxationBound {
        private var multipliers = LongArray(bound.multiplierCount)
        override val applicable: Boolean get() = true

        override fun prune(
            session: PropagationSession,
            effectiveBound: Double,
            objectiveVar: Int,
            objectiveAscending: Boolean,
        ): Boolean {
            val res = bound.computeBound(session, effectiveBound, multipliers, params.lpPlan.lagrangianIterations)
                ?: return false
            multipliers = res.multipliers
            if (res.prune) sink.scheduling.observeLagrangianPrune()
            return res.prune
        }
    }

    /** LP-relaxation simplex bound with depth/freq/auto-off gating, warm-start basis caching and
     *  LP-learned backjump/nogood recording. Sets the outer [lpBackjump] when it derives an
     *  asserting clause (#280). */
    private inner class LpSimplexBound : RelaxationBound {
        override val applicable: Boolean get() = lpRelaxer != null

        override fun prune(
            session: PropagationSession,
            effectiveBound: Double,
            objectiveVar: Int,
            objectiveAscending: Boolean,
        ): Boolean {
            val lpRelaxerL = lpRelaxer ?: return false
            // The residual real check is the model's only decision procedure for its real rows — a
            // demoted LP would leave the search enumerating a space it can never refute — so the
            // adaptive shedding (breaker, ladder, depth/cadence) does not apply to it.
            val essential = params.lpPlan.realResidual && !residualOversized
            if (params.lpPlan.realResidual && residualOversized) return false
            // Wall-clock breaker: an expensive LP the ladder can't shed in time is shut off here,
            // before the ladder, so the search runs as a bare combinatorial arm.
            if (lpWallBreaker.isTripped && !essential) return false
            if (!essential && (
                    session.decisionLevel > params.lpPlan.boundMaxDepth ||
                        ++lpCheckCounter % params.lpPlan.boundEvery != 0 ||
                        !lpLadder.shouldRun()
                    )
            ) {
                return false
            }
            // The ladder's run rung decides whether during-search cuts separate at this node (#32).
            val cutsAllowed = lpLadder.cutsEnabled
            val depth = session.decisionLevel
            // Warm-start this node's LP from the parent depth's optimal basis (#705): tightening
            // a child's bounds keeps that basis dual-feasible, so the re-solve takes a few pivots.
            val warm = if (params.lpPlan.warmStart && depth - 1 in lpBasisByDepth.indices) {
                lpBasisByDepth[depth - 1]
            } else {
                null
            }
            val solveStart = if (lpWallBreaker.remainingMillis() != null) TimeSource.Monotonic.markNow() else null
            val outcome = lpBoundAndFix(
                lpRelaxerL,
                session,
                effectiveBound,
                sink,
                objectiveVar = objectiveVar,
                objectiveAscending = objectiveAscending,
                cancellation = params.cancellation,
                hints = lpHints,
                learn = params.lpPlan.learn,
                warm = warm,
                cutsAllowed = cutsAllowed,
            )
            // Charge this solve's wall time; a prune (or reaching the ladder's warmup) spares the LP.
            solveStart?.let { lpWallBreaker.charge(it.elapsedNow().inWholeMilliseconds, outcome.prune) }
            if (outcome.basis != null) {
                while (lpBasisByDepth.size <= depth) lpBasisByDepth.add(null)
                lpBasisByDepth[depth] = outcome.basis
            }
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
            lpLadder.record(outcome.prune)
            return outcome.prune
        }
    }

    /** The per-node prune cascade, in short-circuit order: cheap lower bound → scheduling-feasibility
     *  bounds → Lagrangian bounds → LP relaxation. [pruneNode] tries each in turn; the first true
     *  prune wins, exactly as the former hand-coded `when`. */
    private val bounds: List<RelaxationBound> = listOfNotNull(
        LinearBound(),
        energeticBound?.let { SchedulingFeasibilityArm(it, params.lpPlan.energeticEvery) },
        cumulativeFlowBound?.let { SchedulingFeasibilityArm(it, params.lpPlan.cumulativeFlowEvery) },
        lagBound?.let { LagrangianArm(it) },
        knapsackLagBound?.let { LagrangianArm(it) },
        LpSimplexBound(),
    )

    /**
     * Drop each convex hull that adds no strength to the root LP optimum, rebuilding the relaxer once
     * over the survivors. Per-factor hulls (every factor whose [com.eignex.klause.solver.Factor.linearize]
     * emits a [com.eignex.klause.lp.Contribution.HULL] row) are pruned individually: each is solved
     * out in turn and kept only if its removal loosens the optimum, so two factors of the same family
     * are judged separately. The non-per-factor hulls (cumulative time-indexed, diffn, Boolean RLT) are
     * emitted by the driver, not a factor, so they carry no per-factor tag and are pruned by whole
     * family. A hull whose removal leaves the optimum unchanged (often because root propagation already
     * achieves the same bound) is pure per-node build cost and is dropped. This is the per-technique
     * counterpart of [LpEffortLadder]'s whole-simplex demotion, decided once before the persistent base
     * is first built.
     *
     * Comparison uses the true LP optimum ([rootLpObjective]), not the safe under-estimate, so a hull's
     * real tightening is visible. Sound: a hull is a sound relaxation whether present or not, and one is
     * dropped only when the root optimum is identical without it. Costs one extra root solve per hull
     * candidate, bounded by [cancellation] (the shared root budget). A no-op when bounding is off or no
     * hull is enabled.
     */
    fun pruneIneffectiveHulls(cancellation: Cancellation) {
        val relaxer = lpRelaxer ?: return
        val full = rootLpObjective(relaxer, cancellation)
        if (full.isNaN()) return
        // Per-factor: greedily solve out each tagged hull contribution; removing a hull can only loosen a
        // minimisation optimum, so an unchanged optimum (within tol) means it added no root strength. A
        // NaN probe (the hull is the only structure) keeps it. Candidates are the factors that emitted a
        // HULL row in the full build.
        val suppressed = mutableSetOf<Int>()
        for (factorId in relaxer.build(PropagationSession(problem)).hullFactorIds) {
            val probe = buildRelaxer(params.lpPlan, suppressed + factorId) ?: continue
            val bound = rootLpObjective(probe, cancellation)
            if (!bound.isNaN() && bound >= full - HULL_PRUNE_TOL) suppressed.add(factorId)
        }
        // Per-family: the driver-emitted hulls with no per-factor tag.
        var plan = params.lpPlan
        for (disable in HULL_DISABLERS) {
            val candidate = disable(plan) ?: continue // null when this hull is already off
            val probe = buildRelaxer(candidate, suppressed) ?: continue
            val bound = rootLpObjective(probe, cancellation)
            if (!bound.isNaN() && bound >= full - HULL_PRUNE_TOL) plan = candidate
        }
        if (plan !== params.lpPlan || suppressed.isNotEmpty()) lpRelaxer = buildRelaxer(plan, suppressed)
    }

    private companion object {
        /** A root optimum this close to the all-hulls optimum counts as "no strength added". */
        const val HULL_PRUNE_TOL = 1e-6

        /** One disabler per prunable hull that is **not** per-factor (emitted by the driver, not a
         *  Linearizer, so it carries no [com.eignex.klause.lp.Contribution] tag): returns the plan
         *  with that family off, or null when it is already off. The per-factor convex hulls are pruned
         *  individually by [pruneIneffectiveHulls]; the base relaxation, objective cone and circuit arcs
         *  are not hulls and are excluded. */
        val HULL_DISABLERS: List<(LpPlan) -> LpPlan?> = listOf(
            { p -> if (p.cumulativeTimeIndexed) p.copy(cumulativeTimeIndexed = false) else null },
            { p -> if (p.diffn) p.copy(diffn = false) else null },
            { p -> if (p.booleanRlt) p.copy(booleanRlt = false) else null },
        )
    }
}

/** Row cap for per-node / per-leaf exact residual work (one LU at ~1k rows is already ~ms-scale). */
private const val RESIDUAL_MAX_ROWS = 1000

/** Eta-chain length of the persistent gated-residual simplex before it refactorizes: its pivots
 *  accumulate across nodes, so the chain must span many node re-solves for the kept factorization to
 *  pay; each eta costs one extra sparse pass per FTRAN/BTRAN, bounding the drift. */
private const val GATED_ETA_LIMIT = 400
