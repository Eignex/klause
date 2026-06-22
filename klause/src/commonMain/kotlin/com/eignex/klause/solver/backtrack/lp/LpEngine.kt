package com.eignex.klause.solver.backtrack.lp

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.lp.Basis
import com.eignex.klause.solver.lp.bound.CumulativeEnergeticBound
import com.eignex.klause.solver.lp.bound.CumulativeFlowBound
import com.eignex.klause.solver.lp.bound.KnapsackLagrangianBound
import com.eignex.klause.solver.lp.bound.LagrangianBound
import com.eignex.klause.solver.lp.cut.AggregationMirSeparator
import com.eignex.klause.solver.lp.cut.AllDifferentSeparator
import com.eignex.klause.solver.lp.cut.AssignmentObjectiveCut
import com.eignex.klause.solver.lp.cut.CircuitSeparator
import com.eignex.klause.solver.lp.cut.CliqueCutSeparator
import com.eignex.klause.solver.lp.cut.Cut
import com.eignex.klause.solver.lp.cut.CutExchange
import com.eignex.klause.solver.lp.cut.CutPool
import com.eignex.klause.solver.lp.cut.CutSeparator
import com.eignex.klause.solver.lp.cut.CutSharing
import com.eignex.klause.solver.lp.cut.FlowCoverSeparator
import com.eignex.klause.solver.lp.cut.GccSeparator
import com.eignex.klause.solver.lp.cut.ImpliedBoundSeparator
import com.eignex.klause.solver.lp.cut.KnapsackCoverSeparator
import com.eignex.klause.solver.lp.cut.SharedCut
import com.eignex.klause.solver.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.solver.lp.relaxation.LpRelaxation
import com.eignex.klause.solver.lp.relaxation.rebound
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.propagation.ConflictAnalyzer.AnalysisResult.Learned
import com.eignex.klause.solver.propagation.PropagationSession
import com.eignex.klause.solver.result.SolveStatsSink

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
    params0: BacktrackParams,
    private val sink: SolveStatsSink,
) {
    /** The relaxation-bound family resolved from the high-level emphasis ([BacktrackParams.lpConfig])
     *  against this problem's structure (#429); with no emphasis set, the explicit
     *  [BacktrackParams.lpPlan] is used verbatim. Resolving here makes the engine the single home for
     *  the intent→plan step, so callers carry only the intent and never a separately-resolved copy. */
    val params: BacktrackParams =
        params0.lpConfig?.let { LpAutoConfig.resolve(problem, it, params0) } ?: params0

    val lpRelaxer = if (params.lpPlan.bounding) {
        CpToLpRelaxation(
            problem,
            objective,
            elementHull = params.lpPlan.element,
            tableHull = params.lpPlan.table,
            cumulative = params.lpPlan.cumulative,
            diffn = params.lpPlan.diffn,
            cumulativeTimeIndexed = params.lpPlan.cumulativeTimeIndexed,
            nValueHull = params.lpPlan.nValue,
            regularHull = params.lpPlan.regular,
            mddHull = params.lpPlan.mdd,
            gccCountHull = params.lpPlan.gccCount,
            circuitArcs = params.lpPlan.circuit,
            objectiveCone = params.lpPlan.objectiveCone,
            linMaxTightFace = params.lpPlan.linMaxTightFace,
            productMcCormick = params.lpPlan.productMcCormick,
            booleanRlt = params.lpPlan.booleanRlt,
        )
    } else {
        null
    }

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
     * Importing only adds globally-valid cuts, so it is sound at any node; new imports invalidate the
     * persistent base so the next node folds them in (mirroring [recordSearchCuts]).
     */
    internal fun exchangeCuts(exchange: CutExchange) {
        val relaxation = persistentRelaxation ?: return
        val before = cutPool.size
        exchange.exchange(
            object : CutSharing {
                override fun exportGlobalCuts(): List<SharedCut> =
                    cutPool.cuts().mapNotNull { if (it.global) SharedCut.fromCut(it, relaxation) else null }

                override fun importCuts(cuts: List<SharedCut>) {
                    for (c in cuts) c.toCut(relaxation)?.let { cutPool.add(it) }
                }
            },
        )
        if (cutPool.size != before) persistentResolved = false
    }

    /**
     * Persist the globally-valid members of [cuts] into the [cutPool] (#41); node-local cuts are
     * ignored here (the caller uses them transiently). When the pool grows, the persistent base is
     * invalidated so the next node rebuilds it with the new cuts, and the pool is trimmed to its cap by
     * activity at [primal]. Sound: every persisted cut is global, valid at every solution.
     */
    fun recordSearchCuts(cuts: List<Cut>, primal: DoubleArray) {
        var added = 0
        for (c in cuts) if (c.global && cutPool.add(c)) added++
        if (added == 0) return
        if (cutPool.size > cutPool.maxCuts) cutPool.retainMostActive(primal)
        persistentResolved = false // rebuild the persistent base with the enlarged pool
    }
    private val lagBound = if (params.lpPlan.lagrangian) {
        LagrangianBound(problem, objective).takeIf { it.applicable }
    } else {
        null
    }
    private var lagMultipliers = LongArray(lagBound?.multiplierCount ?: 0)
    private val knapsackLagBound = if (params.lpPlan.knapsackLagrangian) {
        KnapsackLagrangianBound(problem, objective).takeIf { it.applicable }
    } else {
        null
    }
    private var knapsackLagMultipliers = LongArray(knapsackLagBound?.multiplierCount ?: 0)
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
    private var lpCheckCounter = 0
    private var energeticCheckCounter = 0
    private var cumulativeFlowCheckCounter = 0

    // Adaptive LP effort ladder (#32, generalizing the #614 auto-off): the emphasis sets the ceiling
    // rung (cuts when enabled, else the bare bound), and a rolling prune-rate window descends one rung
    // at a time — shedding during-search cuts before the bound — re-probing upward on backoff. Sound:
    // every rung is a valid bound / off, so the gate only changes work, never solutions.
    private val lpLadder = LpEffortLadder(
        top = LpEffort.ceiling(cutsPermitted = params.lpPlan.cuts),
        reprobeBase = if (params.lpPlan.autoOffReprobe) LpEffortLadder.DEFAULT_REPROBE_BASE else Int.MAX_VALUE,
    )
    val lpNogoods: LpNogoodPool? = if (params.lpPlan.learn) LpNogoodPool() else null
    private val lpBasisByDepth = ArrayList<Basis?>()
    val lpHints = if (params.lpPlan.branching) LpHints(problem.numIntVars, problem.numBoolVars) else null
    private var lpBackjump: Learned? = null

    // Persistent global LP (#39): for a node-invariant relaxation (no auxiliary columns, no live-M
    // rows) the per-node delta is column bounds only, so the relaxation is built once from the declared
    // domains and re-bound at each node instead of rebuilt. `resolved` makes the one-time probe lazy —
    // it runs after the root-cut harvest so the global cuts are folded into the persistent structure.
    private var persistentResolved = false
    private var persistentRelaxation: LpRelaxation? = null

    /**
     * The LP relaxation for the current node (#39): the persistent relaxation re-bound to [session]'s
     * live column bounds when eligible, else a fresh per-node build. On first call it builds a base
     * relaxation from the declared domains (with the harvested [globalCuts]); if that base is
     * [LpRelaxation.persistentEligible] it is cached and every node re-binds it — bit-identical to a
     * rebuild for eligible models, but skipping the matrix reconstruction.
     */
    internal fun nodeRelaxation(
        relaxer: CpToLpRelaxation,
        session: PropagationSession,
        globalCuts: List<Cut>,
    ): LpRelaxation {
        if (!persistentResolved) {
            persistentResolved = true
            val base = relaxer.build(PropagationSession(problem), globalCuts)
            if (base.persistentEligible) persistentRelaxation = base
        }
        return persistentRelaxation?.rebound(session) ?: relaxer.build(session, globalCuts)
    }

    /** The asserting LP backjump clause derived during the last [pruneNode] (#280), or null. */
    fun lastBackjump(): Learned? = lpBackjump

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

    /** Energetic-reasoning scheduling-feasibility bound (#562). */
    private inner class EnergeticBoundArm : RelaxationBound {
        override val applicable: Boolean get() = energeticBound != null

        override fun prune(
            session: PropagationSession,
            effectiveBound: Double,
            objectiveVar: Int,
            objectiveAscending: Boolean,
        ): Boolean {
            val energeticBoundL = energeticBound ?: return false
            if (++energeticCheckCounter % params.lpPlan.energeticEvery != 0 || !energeticBoundL.isInfeasible(session)) {
                return false
            }
            sink.observeEnergeticPrune()
            val lpNogoodsL = lpNogoods
            if (lpNogoodsL != null) energeticBoundL.explain(session)?.let { lpNogoodsL.add(it) }
            return true
        }
    }

    /** Cumulative-flow scheduling-feasibility bound — same prune family as energetic. */
    private inner class CumulativeFlowBoundArm : RelaxationBound {
        override val applicable: Boolean get() = cumulativeFlowBound != null

        override fun prune(
            session: PropagationSession,
            effectiveBound: Double,
            objectiveVar: Int,
            objectiveAscending: Boolean,
        ): Boolean {
            val cumulativeFlowBoundL = cumulativeFlowBound ?: return false
            if (++cumulativeFlowCheckCounter % params.lpPlan.cumulativeFlowEvery != 0 ||
                !cumulativeFlowBoundL.isInfeasible(session)
            ) {
                return false
            }
            sink.observeEnergeticPrune() // same scheduling-feasibility-prune family
            val lpNogoodsL = lpNogoods
            if (lpNogoodsL != null) cumulativeFlowBoundL.explain(session)?.let { lpNogoodsL.add(it) }
            return true
        }
    }

    /** Lagrangian dual bound (#429); reassigns the persistent multiplier vector each call. */
    private inner class LagrangianArm : RelaxationBound {
        override val applicable: Boolean get() = lagBound != null

        override fun prune(
            session: PropagationSession,
            effectiveBound: Double,
            objectiveVar: Int,
            objectiveAscending: Boolean,
        ): Boolean {
            val lagBoundL = lagBound ?: return false
            val res = lagBoundL.computeBound(
                session,
                effectiveBound,
                lagMultipliers,
                params.lpPlan.lagrangianIterations,
            )
            return if (res != null) {
                lagMultipliers = res.multipliers
                if (res.prune) sink.observeLagrangianPrune()
                res.prune
            } else {
                false
            }
        }
    }

    /** Knapsack-Lagrangian dual bound; reassigns the persistent knapsack multiplier vector. */
    private inner class KnapsackLagrangianArm : RelaxationBound {
        override val applicable: Boolean get() = knapsackLagBound != null

        override fun prune(
            session: PropagationSession,
            effectiveBound: Double,
            objectiveVar: Int,
            objectiveAscending: Boolean,
        ): Boolean {
            val knapsackLagBoundL = knapsackLagBound ?: return false
            val res = knapsackLagBoundL.computeBound(
                session,
                effectiveBound,
                knapsackLagMultipliers,
                params.lpPlan.lagrangianIterations,
            )
            return if (res != null) {
                knapsackLagMultipliers = res.multipliers
                if (res.prune) sink.observeLagrangianPrune()
                res.prune
            } else {
                false
            }
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
            if (session.decisionLevel > params.lpPlan.boundMaxDepth ||
                ++lpCheckCounter % params.lpPlan.boundEvery != 0 ||
                !lpLadder.shouldRun()
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
            val outcome = lpBoundAndFix(
                lpRelaxerL,
                session,
                effectiveBound,
                sink,
                objectiveVar = objectiveVar,
                objectiveAscending = objectiveAscending,
                globalCuts = lpGlobalCuts,
                cancellation = params.cancellation,
                hints = lpHints,
                learn = params.lpPlan.learn,
                warm = warm,
                cutsAllowed = cutsAllowed,
            )
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
    private val bounds: List<RelaxationBound> = listOf(
        LinearBound(),
        EnergeticBoundArm(),
        CumulativeFlowBoundArm(),
        LagrangianArm(),
        KnapsackLagrangianArm(),
        LpSimplexBound(),
    )
}
