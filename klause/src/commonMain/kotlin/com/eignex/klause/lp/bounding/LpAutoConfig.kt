package com.eignex.klause.lp.bounding

import com.eignex.klause.bound.CumulativeEnergeticBound
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearIndicator
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.bool.internals.permitsLongPseudoBoolean
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.ElementLpChoices
import com.eignex.klause.lp.HullFamily
import com.eignex.klause.lp.RootBoxes
import com.eignex.klause.lp.lpHullFamily
import com.eignex.klause.lp.prepareLpHull
import com.eignex.klause.lp.prepareRootBoxes
import com.eignex.klause.lp.prepareStatesBothBounds
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.CumulativeRelaxation
import com.eignex.klause.lp.relaxation.LpSourcePreparation
import com.eignex.klause.lp.relaxation.prepareCandidateArcCount
import com.eignex.klause.lp.relaxation.prepareSchedulingViews
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.ExecutionControl

internal data class LpAdmission(
    val estimatedRows: Long,
    val estimatedColumns: Long,
    val estimatedCells: Long,
    val structurallyApplicable: Boolean,
    val boundingPermitted: Boolean,
    val withinCeiling: Boolean,
    val explicitBounding: Boolean,
    val plan: LpPlan,
    val elementIndexes: Set<Int>? = null,
    val sparseTrial: SparseLpAdmission? = null,
    val observation: LpRootAdmissionObservation? = null,
)

/**
 * Structural auto-configuration of the LP-relaxation family. Each technique is enabled only when the
 * problem contains the structure it targets and the [LpConfig] emphasis permits its cost tier:
 *
 *  - **[LpPlan.bounding]**: LP-emittable rows ([Linear], [ReifiedLinear], ...) or a global whose
 *    relaxation or cuts need the LP. With it come objective propagation, Farkas learning
 *    ([LpPlan.learn]) and the LP-rounding probe ([LpPlan.probe]).
 *  - **[LpPlan.cuts]**: LP bounding is active and the cost tier permits separation.
 *  - **Hulls** ([LpPlan.circuit], [LpPlan.element], [LpPlan.table], [LpPlan.nValue], ...): the
 *    matching global ([Circuit], [Element], [Table], [NValue], ...) is present.
 *  - **[LpPlan.lagrangian]**: an [AllDifferent] is present.
 *  - **[LpPlan.energeticReasoning]**: a [Cumulative] is present. When this path enables it,
 *    [LpPlan.energeticEvery] is derived from the task counts so the window scan stays within
 *    [ENERGETIC_OPS_PER_CHECK] per check.
 *  - **[LpPlan.cumulative]**: [CumulativeRelaxation] proves a makespan link for a scheduling global.
 *
 * A **size guard** bounds per-node solve cost. LP is declined once the estimated relaxation size
 * (`rows × (cols + rows + 1)`) exceeds [KlauseConfig.lpCeilingTableauCells]. The gated hulls then compete
 * smallest-first for a hull budget: [KlauseConfig.lpMaxTableauCells] when the base relaxation fits it, else
 * the ceiling. The Lagrangian and energetic bounds have their own caps. Every flag is OR-ed onto `base`, so
 * an explicit caller setting bypasses the guard and is never turned off. An explicit
 * [LpTechnique.SPARSE_TRIAL] request can instead select a resource-bounded root plan after inspecting the
 * emitted linear relaxation.
 */
object LpAutoConfig {

    /**
     * Per-check operation budget the auto-derived [LpPlan.energeticEvery] normalises to:
     * `cadence = ceil(Σ tasksᵢ³ / budget)` over the Cumulatives within the scan's own task cap.
     */
    const val ENERGETIC_OPS_PER_CHECK: Long = 1L shl 17

    /**
     * At [LpEmphasis.DEFAULT] an [LpTiming.EXHAUSTIVE] hull is still admitted when its estimated added
     * `cols + rows` stay at most this: the per-node simplex already pays for an LP, and a small hull only
     * tightens it. The size budget still caps the admitted stack.
     */
    const val SMALL_HULL_DIM: Long = 128L

    /** [resolve] at the [LpEmphasis.AGGRESSIVE] ceiling. */
    fun recommend(problem: Problem, base: LpPlan = LpPlan()): LpPlan = resolve(problem, LpConfig.AGGRESSIVE, base)

    /**
     * `base` with each LP technique enabled where [problem]'s structure makes it applicable and [config]
     * permits it. Flags are OR-ed onto `base`, so an explicit caller setting is never turned off.
     */
    @Suppress("CyclomaticComplexMethod")
    fun resolve(problem: Problem, config: LpConfig, base: LpPlan = LpPlan()): LpPlan {
        var result: LpPlan? = null
        sequence<Unit> { result = prepare(problem, config, base) }.forEach { }
        return checkNotNull(result)
    }

    internal fun resolve(
        problem: Problem,
        config: LpConfig,
        base: LpPlan,
        objective: LinearObjective?,
    ): LpPlan {
        var result: LpPlan? = null
        sequence<Unit> { result = prepare(problem, config, base, objective) }.forEach { }
        return checkNotNull(result)
    }

    internal fun admission(problem: Problem, config: LpConfig, base: LpPlan): LpAdmission {
        var result: LpAdmission? = null
        sequence { prepare(problem, config, base) { result = it } }.forEach { }
        return checkNotNull(result)
    }

    internal suspend fun SequenceScope<Unit>.prepare(
        problem: Problem,
        config: LpConfig,
        base: LpPlan,
        objective: LinearObjective? = null,
        cancellation: ExecutionControl = ExecutionControl.Never,
        sparseTrialAllowed: Boolean = true,
        sparseTrialWork: Long = SPARSE_LP_MAX_WORK,
        sparseTrialQueryWork: Long = SPARSE_LP_MAX_WORK,
        sourcePreparation: LpSourcePreparation = config.sourcePreparation ?: base.sourcePreparation,
        allowAutomaticSparse: Boolean = false,
        captureSparseAdmission: Boolean = false,
        observe: ((LpAdmission) -> Unit)? = null,
    ): LpPlan {
        var visits = 0
        var lpEmittable = false
        val binaryIntegerView = objective?.binaryIntegerSource != null
        var allDifferent = false
        var globalCardinality = false
        var cumulative = false
        var scheduling = false
        var energeticOps = 0L
        var pseudoBoolean = false
        var circuit = false
        var constArrayElement = false
        var table = false
        var nValue = false
        var regular = false
        var mdd = false
        var gccCount = false
        var diffn = false
        var arrayMinMax = false
        var product = false
        var rows = 0L
        for (f in problem.factors) {
            if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            if (f is Linear) {
                lpEmittable = true
                rows += 1
                continue
            }
            when (f) {
                is ExactLinear, is LinearIndicator -> {
                    lpEmittable = true
                    rows += 1
                }

                is ReifiedLinear -> {
                    lpEmittable = true
                    rows += 2
                }

                is ReifiedRealLinear -> {
                    lpEmittable = true
                    rows += 2
                }

                is ReifiedPseudoBoolean -> {
                    if (!permitsLongPseudoBoolean(f.constants)) continue
                    lpEmittable = true
                    rows += 2
                }

                is ReifiedCardinality -> {
                    lpEmittable = true
                    rows += 2
                }

                is Cardinality -> {
                    if (binaryIntegerView) lpEmittable = true
                    rows += 2
                }

                is Clause -> {
                    if (binaryIntegerView) lpEmittable = true
                    rows += 1
                }

                is AllDifferent -> allDifferent = true

                is GlobalCardinality -> {
                    globalCardinality = true
                    if (f.countVars != null && f.presents.isEmpty()) gccCount = true
                }

                is Cumulative -> {
                    cumulative = true
                    scheduling = true
                    // The scan skips factors above its own task cap.
                    val t = f.starts.size.toLong()
                    if (t <= CumulativeEnergeticBound.MAX_TASKS) energeticOps += t * t * t
                }

                is PseudoBoolean -> {
                    if (!permitsLongPseudoBoolean(f.constants)) continue
                    pseudoBoolean = true
                    rows += 1
                }

                is Circuit -> circuit = true

                // A variable-array Element builds the big-M form of the hull.
                is Element -> constArrayElement = true

                is ArrayMinMax -> arrayMinMax = true

                is Product -> product = true

                is Table -> table = true

                is NValue -> nValue = true

                is Regular -> regular = true

                is Mdd -> mdd = true

                is Diffn -> diffn = true

                else -> Unit
            }
        }
        val makespanPlans = if (scheduling) {
            val owner = CumulativeRelaxation(problem, deferred = true)
            owner.preparation().forEach { yield(Unit) }
            owner.plans.size
        } else {
            0
        }
        rows += makespanPlans.toLong()
        // Counted apart from the cumulative plans so Diffn gates under its own technique.
        val diffnPlans = if (diffn) {
            val owner = CumulativeRelaxation(problem, includeCumulative = false, includeDiffn = true, deferred = true)
            owner.preparation().forEach { yield(Unit) }
            owner.plans.size
        } else {
            0
        }
        rows += diffnPlans.toLong()
        val baseCols = problem.numIntVars.toLong() + problem.numBoolVars.toLong() + problem.numRealVars.toLong()
        // The base cap bounds the hull budget of a small base relaxation; the ceiling is the size past
        // which LP is declined.
        val baseCap = problem.settings.lpMaxTableauCells
        val ceilingCap = problem.settings.lpCeilingTableauCells
        val cells = tableauCells(rows, baseCols)

        val cutEligible = allDifferent || globalCardinality
        val structApplicable =
            lpEmittable || cutEligible || pseudoBoolean || circuit || constArrayElement ||
                table || nValue || regular || mdd || gccCount || makespanPlans > 0 || diffnPlans > 0 ||
                arrayMinMax || product
        // An objective-cone request drops the big-M rows and every variable disconnected from the
        // objective, so it is treated as fitting every cap.
        val coneRequested = base.objectiveCone
        val baseFits = coneRequested || cells <= baseCap
        val lpActive = structApplicable && config.resolved(LpTechnique.BOUNDING) &&
            (coneRequested || cells <= ceilingCap)

        val makespanLp = lpActive && makespanPlans > 0
        val diffnLp = lpActive && diffnPlans > 0
        val hullCap = if (baseFits) baseCap else ceilingCap

        // Each estimate honours its hull's own MAX_* cap exactly as the builder does.
        val candidates = if (!lpActive) {
            emptyList()
        } else {
            buildList {
                fun admit(e: HullEstimate?) = e?.let { if (hullAdmitted(config, it)) add(it) }
                // The root-box reading copies the whole table, so it is materialized once, on the first
                // family that needs it.
                var boxes: RootBoxes? = null
                suspend fun SequenceScope<Unit>.rootBoxes(): RootBoxes =
                    boxes ?: prepareRootBoxes(problem).also { boxes = it }
                if (circuit) admit(circuitEstimate(problem))
                if (constArrayElement) {
                    for (estimate in elementEstimates(problem, rootBoxes())) admit(estimate)
                }
                if (table) admit(hullEstimate(problem, rootBoxes(), LpTechnique.TABLE, HullFamily.TABLE))
                if (nValue) admit(hullEstimate(problem, rootBoxes(), LpTechnique.NVALUE, HullFamily.NVALUE))
                if (regular) admit(hullEstimate(problem, rootBoxes(), LpTechnique.REGULAR, HullFamily.REGULAR))
                if (mdd) admit(hullEstimate(problem, rootBoxes(), LpTechnique.MDD, HullFamily.MDD))
                if (gccCount) admit(hullEstimate(problem, rootBoxes(), LpTechnique.GCC_COUNT, HullFamily.GCC_COUNT))
                if (scheduling) admit(timeIndexedEstimate(problem))
            }
        }
        val selection = acceptUnderBudget(rows, baseCols, candidates, hullCap)
        val acceptedHulls = selection.techniques

        val cuts = lpActive && config.resolved(LpTechnique.CUTS)
        val structuralCuts = cuts && (cutEligible || pseudoBoolean)
        val energetic = cumulative && config.resolved(LpTechnique.ENERGETIC)
        var plan = base.copy(
            sourcePreparation = sourcePreparation,
            bounding = base.bounding || lpActive,
            cuts = base.cuts || cuts,
            // The objective-bound reason reuses the exact basis certificate the bounding solve computes.
            learn = base.learn || lpActive,
            probe = base.probe || lpActive,
            circuit = base.circuit || (LpTechnique.CIRCUIT in acceptedHulls),
            element = base.element || (LpTechnique.ELEMENT in acceptedHulls),
            table = base.table || (LpTechnique.TABLE in acceptedHulls),
            nValue = base.nValue || (LpTechnique.NVALUE in acceptedHulls),
            regular = base.regular || (LpTechnique.REGULAR in acceptedHulls),
            mdd = base.mdd || (LpTechnique.MDD in acceptedHulls),
            gccCount = base.gccCount || (LpTechnique.GCC_COUNT in acceptedHulls),
            cumulative = base.cumulative || makespanLp,
            diffn = base.diffn || (diffnLp && config.resolved(LpTechnique.DIFFN)),
            cumulativeTimeIndexed = base.cumulativeTimeIndexed ||
                (LpTechnique.CUMULATIVE_TIME_INDEXED in acceptedHulls),
            cumulativeFlow = base.cumulativeFlow ||
                (scheduling && config.resolved(LpTechnique.CUMULATIVE_FLOW)),
            linMaxTightFace = base.linMaxTightFace ||
                (lpActive && arrayMinMax && config.resolved(LpTechnique.LINMAX_TIGHT)),
            productMcCormick = base.productMcCormick ||
                (lpActive && product && config.resolved(LpTechnique.PRODUCT_MCCORMICK)),
            impliedBoundCuts = base.impliedBoundCuts ||
                (structuralCuts && problem.numBoolVars > 0 && config.resolved(LpTechnique.IMPLIED_BOUND)),
            flowCoverCuts = base.flowCoverCuts || (structuralCuts && config.resolved(LpTechnique.FLOW_COVER)),
            booleanRlt = base.booleanRlt || (structuralCuts && config.resolved(LpTechnique.BOOLEAN_RLT)),
            lagrangian = base.lagrangian || (allDifferent && config.resolved(LpTechnique.LAGRANGIAN)),
            energeticReasoning = base.energeticReasoning || energetic,
            // An explicit caller enablement keeps the caller's cadence.
            energeticEvery = if (energetic && !base.energeticReasoning) {
                maxOf(base.energeticEvery.toLong(), 1L + (energeticOps - 1L) / ENERGETIC_OPS_PER_CHECK)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                base.energeticEvery
            },
        )
        val sparseOverride = config.overrides[LpTechnique.SPARSE_TRIAL]
        val explicitSparse = sparseOverride == true
        val sparsePermitted = explicitSparse || (allowAutomaticSparse && config.permitsAutomaticSparse())
        val trace = if (captureSparseAdmission) SparseAdmissionTrace() else null
        val automaticSparse = sparseGuard(trace, true, 0, !explicitSparse) &&
            sparseGuard(trace, true, 1, sparsePermitted) &&
            sparseGuard(trace, true, 2, !base.bounding) &&
            sparseGuard(trace, true, 3, objective != null) &&
            sparseGuard(trace, true, 4, problem.numRealVars == 0) &&
            sparseGuard(trace, true, 5, !problem.intBounds.hasOpenSides) &&
            sparseGuard(trace, true, 6, !base.componentOwnership) &&
            sparseGuard(trace, true, 7, !base.lbTreeSearch) &&
            sparseGuard(trace, true, 8, !coneRequested) &&
            sparseGuard(trace, true, 9, cells > baseCap) &&
            sparseGuard(trace, true, 10, !plan.cuts) &&
            sparseGuard(trace, true, 11, !plan.booleanRlt) &&
            sparseGuard(trace, true, 12, !plan.impliedBoundCuts) &&
            sparseGuard(trace, true, 13, !plan.flowCoverCuts) &&
            sparseGuard(trace, true, 14, config.overrides.none { (technique, enabled) ->
                enabled && technique.timing == LpTiming.EXHAUSTIVE
            })
        val sparse = if (sparseGuard(trace, false, 0, true) &&
            sparseGuard(trace, false, 1, sparsePermitted) &&
            sparseGuard(trace, false, 2, !base.bounding) &&
            sparseGuard(trace, false, 3, explicitSparse || automaticSparse) &&
            sparseGuard(trace, false, 4, structApplicable) &&
            sparseGuard(trace, false, 5, config.resolved(LpTechnique.BOUNDING))
        ) {
            if (sparseTrialAllowed) prepareSparseLpAdmission(problem, objective, cancellation,
                minOf(sparseTrialWork, base.rootMaxWork.takeIf { it > 0L } ?: SPARSE_LP_MAX_WORK).also {
                    trace?.helperPreparationWork = it
                }, sourcePreparation, automatic = automaticSparse,
                queryWorkAllowance = minOf(sparseTrialQueryWork,
                    base.rootMaxWork.takeIf { it > 0L } ?: SPARSE_LP_MAX_WORK).also {
                    trace?.helperQueryWork = it
                }).also {
                trace?.disposition = if (it.admitted) LpRootAdmissionDisposition.ADMITTED
                else LpRootAdmissionDisposition.ATTEMPTED_DECLINED
            }
            else SparseLpAdmission(mapOf("DeclineProjection" to 1L,
                "Automatic" to if (automaticSparse) 1L else 0L)).also {
                trace?.disposition = LpRootAdmissionDisposition.PROJECTION_SKIPPED
            }
        } else null
        if (sparse?.admitted == true) {
            plan = (if (explicitSparse) LpPlan() else plan).copy(
                bounding = true, boundMaxDepth = if (automaticSparse && plan.bounding) plan.boundMaxDepth else 0,
                learn = true, probe = true, rootCutHarvest = false,
                rootMaxWork = minOf(base.rootMaxWork.takeIf { it > 0L } ?: SPARSE_LP_MAX_WORK, SPARSE_LP_MAX_WORK,
                    if (automaticSparse) sparseTrialQueryWork else SPARSE_LP_MAX_WORK),
                rootBudgetFraction = base.rootBudgetFraction, rootBudgetMillis = base.rootBudgetMillis,
                lpWorkBudgetFraction = base.lpWorkBudgetFraction, boundMaxPivots = base.boundMaxPivots,
                boundEvery = base.boundEvery, warmStart = base.warmStart, branching = base.branching,
                componentOwnership = base.componentOwnership, componentReuse = base.componentReuse,
                sourcePreparation = sourcePreparation,
            )
        }
        observe?.invoke(LpAdmission(rows, baseCols, cells, structApplicable,
            config.resolved(LpTechnique.BOUNDING).also { trace?.boundingPermitted = it },
            (coneRequested || cells <= ceilingCap).also { trace?.withinCeiling = it }, base.bounding, plan,
            if (base.element) null else selection.elementIndexes.toSet(), sparse,
            trace?.snapshot(config.emphasis, sparseOverride, sparsePermitted, automaticSparse,
                rows, baseCols, cells, baseCap, ceilingCap, structApplicable, sparseTrialAllowed,
                sparseTrialQueryWork, sparseTrialWork, plan, sparse?.metrics.orEmpty())))
        return plan
    }

    // Size proxy, not an allocation: m rows times n structural columns plus m slacks and the rhs.
    private fun tableauCells(rows: Long, cols: Long): Long = rows * (cols + rows + 1L)

    private class HullEstimate(
        val key: LpTechnique,
        val cols: Long,
        val rows: Long,
        val elementIndex: Int? = null,
    )

    private class HullSelection(val techniques: Set<LpTechnique>, val elementIndexes: Set<Int>)

    private fun hullAdmitted(config: LpConfig, h: HullEstimate): Boolean {
        config.overrides[h.key]?.let { return it }
        if (h.key.timing in config.emphasis.timings) return true
        return LpTiming.MEDIUM in config.emphasis.timings && h.cols + h.rows <= SMALL_HULL_DIM
    }

    // Smallest-first, so a stack of hulls sheds its largest members instead of pushing the per-node LP
    // past the budget.
    private fun acceptUnderBudget(
        baseRows: Long,
        baseCols: Long,
        candidates: List<HullEstimate>,
        maxCells: Long,
    ): HullSelection {
        var r = baseRows
        var c = baseCols
        val accepted = HashSet<LpTechnique>()
        val elementIndexes = LinkedHashSet<Int>()
        for (h in candidates.sortedWith(compareBy<HullEstimate> { it.cols + it.rows }.thenBy { it.elementIndex })) {
            if (tableauCells(r + h.rows, c + h.cols) <= maxCells) {
                r += h.rows
                c += h.cols
                accepted.add(h.key)
                h.elementIndex?.let(elementIndexes::add)
            }
        }
        return HullSelection(accepted, elementIndexes)
    }

    private suspend fun SequenceScope<Unit>.elementEstimates(problem: Problem, boxes: RootBoxes): List<HullEstimate> {
        var visits = 0
        val groups = LinkedHashMap<Int, MutableList<Element>>()
        for (factor in problem.factors) {
            if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            if (factor is Element) groups.getOrPut(factor.idx) { ArrayList() }.add(factor)
        }
        val estimates = ArrayList<HullEstimate>()
        for ((index, factors) in groups) {
            val choices = ElementLpChoices()
            var cols = 0L
            var rows = 0L
            var complete = true
            for (factor in factors) {
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
                val estimate = prepareLpHull(factor, boxes, choices)
                if (estimate == null) {
                    complete = false
                    break
                }
                cols += estimate.cols
                rows += estimate.rows
            }
            if (complete) estimates.add(HullEstimate(LpTechnique.ELEMENT, cols, rows, index))
        }
        return estimates
    }

    // Mirrors `CpToLpRelaxation.buildArcModel`.
    private suspend fun SequenceScope<Unit>.circuitEstimate(problem: Problem): HullEstimate? {
        var visits = 0
        var cols = 0L
        var rows = 0L
        var any = false
        for (f in problem.factors) {
            if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            val circuitFactor = f as? Circuit ?: continue
            val succ = circuitFactor.succ
            val selfLoops = circuitFactor.subcircuit
            val n = succ.size
            if (n < 2) continue
            // The build declines an open-sided successor.
            if (!prepareStatesBothBounds(problem, succ)) continue
            val arcs = prepareCandidateArcCount(
                succ, selfLoops, CpToLpRelaxation.MAX_CIRCUIT_ARCS, problem::rootDomainOf,
            )
            if (arcs == 0 || arcs > CpToLpRelaxation.MAX_CIRCUIT_ARCS) continue
            any = true
            cols += arcs
            rows += 3L * n // out-degree + channel + in-degree rows (upper bound)
        }
        return if (any) HullEstimate(LpTechnique.CIRCUIT, cols, rows) else null
    }

    // Sums the per-factor estimate the build also uses, so gating cannot drift from the emitted rows.
    private suspend fun SequenceScope<Unit>.hullEstimate(
        problem: Problem,
        boxes: RootBoxes,
        technique: LpTechnique,
        family: HullFamily,
    ): HullEstimate? {
        var visits = 0
        var cols = 0L
        var rows = 0L
        var any = false
        for (f in problem.factors) {
            if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            if (f.lpHullFamily() != family) continue
            val e = prepareLpHull(f, boxes) ?: continue
            cols += e.cols
            rows += e.rows
            any = true
        }
        return if (any) HullEstimate(technique, cols, rows) else null
    }

    // Mirrors `buildCumulativeTimeIndexed`, including its horizon and column caps.
    private suspend fun SequenceScope<Unit>.timeIndexedEstimate(problem: Problem): HullEstimate? {
        var visits = 0
        var cols = 0L
        var rows = 0L
        var any = false
        for (v in prepareSchedulingViews(problem)) {
            if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            // The build declines a view with an open-sided start.
            if (!prepareStatesBothBounds(problem, v.starts)) continue
            val n = v.starts.size
            var t0 = Long.MAX_VALUE
            var t1 = Long.MIN_VALUE
            var c = 0L
            var ok = true
            for (i in 0 until n) {
                if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
                val dom = problem.rootDomainOf(v.starts[i])
                if (dom.max < dom.min) {
                    ok = false
                    break
                }
                if (dom.min < t0) t0 = dom.min
                val end = dom.max + v.durations[i]
                if (end > t1) t1 = end
                c += dom.max - dom.min + 1
            }
            val horizon = t1 - t0
            if (!ok || horizon <= 0 || horizon > CpToLpRelaxation.MAX_TI_HORIZON || c > CpToLpRelaxation.MAX_TI_COLS) {
                continue
            }
            any = true
            cols += c
            rows += horizon + 2L * n // H resource rows + assignment + channel per task
        }
        return if (any) HullEstimate(LpTechnique.CUMULATIVE_TIME_INDEXED, cols, rows) else null
    }
}

private class SparseAdmissionTrace {
    var disposition = LpRootAdmissionDisposition.NOT_CONSIDERED
    var automaticEvaluated = 0L
    var automaticPassed = 0L
    var outerEvaluated = 0L
    var outerPassed = 0L
    var helperQueryWork: Long? = null
    var helperPreparationWork: Long? = null
    var boundingPermitted: Boolean? = null
    var withinCeiling: Boolean? = null

    fun snapshot(emphasis: LpEmphasis, sparseOverride: Boolean?, sparsePermitted: Boolean,
        automaticEligible: Boolean, rows: Long, columns: Long, cells: Long, baseCap: Long, ceilingCap: Long,
        structurallyApplicable: Boolean, projectionAllowed: Boolean, producerQueryWork: Long,
        producerPreparationWork: Long, plan: LpPlan, metrics: Map<String, Long>): LpRootAdmissionObservation =
        LpRootAdmissionObservation(disposition, emphasis = emphasis, sparseOverride = sparseOverride,
            sparsePermitted = sparsePermitted, automaticEligible = automaticEligible,
            automaticEvaluated = automaticEvaluated, automaticPassed = automaticPassed,
            outerEvaluated = outerEvaluated, outerPassed = outerPassed,
            estimatedRows = rows, estimatedColumns = columns, estimatedCells = cells,
            baseCellCap = baseCap, ceilingCellCap = ceilingCap, boundingPermitted = boundingPermitted,
            withinCeiling = withinCeiling, structurallyApplicable = structurallyApplicable,
            projectionAllowed = projectionAllowed, producerQueryWork = producerQueryWork,
            producerPreparationWork = producerPreparationWork, helperQueryWork = helperQueryWork,
            helperPreparationWork = helperPreparationWork, resolvedBounding = plan.bounding,
            resolvedProbe = plan.probe, resolvedBranching = plan.branching,
            resolvedComponentOwnership = plan.componentOwnership, resolvedLbTreeSearch = plan.lbTreeSearch,
            resolvedRootMaxWork = plan.rootMaxWork, resolvedBoundMaxDepth = plan.boundMaxDepth,
            resolvedRootBudgetMillis = plan.rootBudgetMillis, resolvedRootBudgetFraction = plan.rootBudgetFraction,
            resolvedLpWorkBudgetFraction = plan.lpWorkBudgetFraction, metrics = metrics)
}

private fun sparseGuard(trace: SparseAdmissionTrace?, automatic: Boolean, index: Int, result: Boolean): Boolean {
    if (trace != null) {
        val bit = 1L shl index
        if (automatic) {
            trace.automaticEvaluated = trace.automaticEvaluated or bit
            if (result) trace.automaticPassed = trace.automaticPassed or bit
        } else {
            trace.outerEvaluated = trace.outerEvaluated or bit
            if (result) trace.outerPassed = trace.outerPassed or bit
        }
    }
    return result
}
