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
import com.eignex.klause.lp.relaxation.prepareCandidateArcCount
import com.eignex.klause.lp.relaxation.prepareSchedulingViews
import com.eignex.klause.lp.relaxation.schedulingViews
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.lp.statesBothBounds
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.CONSTRUCTION_BATCH_SIZE
import com.eignex.klause.util.Cancellation

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
)

/**
 * Structural auto-configuration of the LP-relaxation family. Each technique is enabled only when the
 * problem contains the structure it targets and the [LpConfig] emphasis permits its cost tier:
 *
 *  - **[LpPlan.bounding]** — there is genuine integer-linear structure ([Linear] /
 *    [ReifiedLinear]) the relaxation can exploit, or a global whose relaxation/cuts need the LP
 *    ([AllDifferent], [GlobalCardinality], [Circuit], constant-array [Element], [Table]). With it
 *    come the bounding-stack techniques that need no extra structure: per-node objective propagation,
 *    Farkas learning ([LpPlan.learn]) and the LP-rounding incumbent probe
 *    ([LpPlan.probe]).
 *  - **[LpPlan.cuts]** — LP bounding is active and the cost tier permits separation.
 *    Structural separators target their factor families; Gomory/MIR candidates require supported
 *    integral LP columns and exact row data. Source mapping checks rounding and inherited premises
 *    before a cut enters the shared root pool.
 *  - **[LpPlan.circuit]** — a [Circuit] is present (subtour-elimination cuts).
 *  - **[LpPlan.element]** — a constant-array [Element] is present (its convex hull).
 *  - **[LpPlan.table]** — a [Table] is present (its convex hull).
 *  - **[LpPlan.nValue]** — an [NValue] is present (its one-hot value hull).
 *  - **[LpPlan.lagrangian]** — an [AllDifferent] is present (the weighted-assignment bound).
 *  - **[LpPlan.energeticReasoning]** — a [Cumulative] is present. When the auto path is
 *    the one enabling it, [LpPlan.energeticEvery] is derived from the model's task counts
 *    so the O(windows² · tasks) window scan stays within [ENERGETIC_OPS_PER_CHECK] per check;
 *    a caller who enabled the check explicitly keeps their cadence.
 *  - **[LpPlan.cumulative]** — a [Cumulative] with a *verifiable*
 *    makespan variable is present (the energetic makespan LP row). Applicability is the
 *    structural fact that [CumulativeRelaxation] proves a makespan link, so this also lets the
 *    scheduling globals turn the LP bounding stack on; size-gated like the other relaxation flags.
 *
 * The LP-relaxation flags have a **size guard** bounding per-node solve cost. Ordinary LP is declined once
 * the estimated relaxation size (`rows × (cols + rows + 1)`) exceeds the ceiling [KlauseConfig.lpCeilingTableauCells].
 * The gated hulls (circuit / element / table / nvalue / regular / mdd / gcc-count / time-indexed) then compete for a
 * hull budget — the base cap [KlauseConfig.lpMaxTableauCells] when the base relaxation fits it, else the ceiling —
 * accepted smallest-first, so a stack of hulls is shed rather than allowed to defeat the guard. The Lagrangian and
 * energetic bounds have their own internal caps and are not size-gated here. An explicit caller flag bypasses the
 * guard: every flag is OR-ed onto `base`, so an explicit setting is never turned *off*.
 * An explicit [LpTechnique.SPARSE_TRIAL] request can instead select a resource-bounded root plan after inspecting
 * the emitted linear relaxation. This does not expand ordinary node admission or dense exact continuation.
 *
 * `LpEngine` resolves [LpParams.lpConfig] through this; the portfolio calls [resolve] directly to ask
 * whether an arm's emphasis enables any LP technique.
 */
object LpAutoConfig {

    /**
     * Per-check operation budget the auto-derived [LpPlan.energeticEvery] normalises to:
     * `cadence = ceil(Σ tasksᵢ³ / budget)` over the Cumulatives the scan actually visits (factors
     * past the bound's own task cap are skipped there and cost nothing). A 48-task model stays at
     * cadence 1; a 256-task model lands around 128.
     */
    const val ENERGETIC_OPS_PER_CHECK: Long = 1L shl 17

    /**
     * Middle-tier size threshold: at [LpEmphasis.DEFAULT] a convex-hull technique (normally an
     * [LpTiming.EXHAUSTIVE] add-on, so off until `AGGRESSIVE`) is still admitted when its estimated
     * added dimensions `cols + rows` stay under this, since per-node simplex bounding is already paying
     * for an LP there and a small hull only tightens it. The expensive hulls (a large Table / Regular /
     * Mdd / time-indexed expansion) stay gated to `AGGRESSIVE`. Hulls are sound relaxations, so this only
     * trades a little build cost for a tighter bound; the size budget still caps the admitted stack.
     */
    const val SMALL_HULL_DIM: Long = 128L

    /** `base` with every ordinary structurally-applicable LP technique enabled — i.e. [resolve] at the
     *  [LpEmphasis.AGGRESSIVE] ceiling (no cost gating). */
    fun recommend(problem: Problem, base: LpPlan = LpPlan()): LpPlan = resolve(problem, LpConfig.AGGRESSIVE, base)

    /**
     * `base` with each LP technique enabled where [problem]'s structure makes it applicable **and**
     * [config] permits it (the emphasis cost ceiling + per-technique overrides — see [LpConfig]).
     * Structural applicability and the size guard are unchanged; the emphasis just caps
     * which cost tiers may run, so `AGGRESSIVE` reproduces the all-applicable [recommend] and the
     * cheaper levels switch the expensive tiers off. Flags are OR-ed onto `base`, so an explicit
     * caller setting is never turned off.
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
        cancellation: Cancellation = Cancellation.Never,
        sparseTrialAllowed: Boolean = true,
        sparseTrialWork: Long = SPARSE_LP_MAX_WORK,
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
            when (f) {
                is Linear, is ExactLinear, is LinearIndicator -> {
                    lpEmittable = true
                    rows += 1
                }

                is ReifiedLinear -> {
                    lpEmittable = true
                    rows += 2
                }

                // The relaxation gates a reified real row by its indicator: two big-M rows, like a ReifiedLinear.
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
                    // The count-var hull bounds the count variables; only that form has any to bound.
                    if (f.countVars != null && f.presents.isEmpty()) gccCount = true
                }

                is Cumulative -> {
                    cumulative = true
                    scheduling = true
                    // The scan skips factors above its own task cap; they cost nothing.
                    val t = f.starts.size.toLong()
                    if (t <= CumulativeEnergeticBound.MAX_TASKS) energeticOps += t * t * t
                }

                is PseudoBoolean -> {
                    if (!permitsLongPseudoBoolean(f.constants)) continue
                    pseudoBoolean = true
                    rows += 1
                }

                is Circuit -> circuit = true

                // Both constant- and variable-array Element route to the element hull (the variable case
                // builds the big-M form); the size guard still caps it internally.
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
        // Size guard: the per-node LP is the base relaxation PLUS every enabled gated-hull's columns/rows.
        // Estimate each hull against the same MAX_* caps the builders skip at, then accept them
        // smallest-first only while the combined `base + accepted` tableau stays under the budget — so
        // a stack of hulls (Table + NValue + Circuit + time-indexed) can't defeat the guard: the
        // largest are shed and the base LP still runs.
        val makespanPlans = if (scheduling) {
            val owner = CumulativeRelaxation(problem, deferred = true)
            owner.preparation().forEach { yield(Unit) }
            owner.plans.size
        } else {
            0
        }
        rows += makespanPlans.toLong()
        // Diffn contributes the same kind of makespan row, one per derived axis plan (over an existing
        // column), counted separately so it gates under its own technique rather than lpCumulative.
        val diffnPlans = if (diffn) {
            val owner = CumulativeRelaxation(problem, includeCumulative = false, includeDiffn = true, deferred = true)
            owner.preparation().forEach { yield(Unit) }
            owner.plans.size
        } else {
            0
        }
        rows += diffnPlans.toLong()
        val baseCols = problem.numIntVars.toLong() + problem.numBoolVars.toLong() + problem.numRealVars.toLong()
        // Two per-node cost-guard tiers; the bound is sound either way. `tableauCells` is a size proxy,
        // not a literal allocation. The base cap bounds the hull budget of a small base relaxation; the
        // ceiling is the absolute size past which LP is declined.
        val baseCap = problem.settings.lpMaxTableauCells
        val ceilingCap = problem.settings.lpCeilingTableauCells
        val cells = tableauCells(rows, baseCols)

        val cutEligible = allDifferent || globalCardinality
        val structApplicable =
            lpEmittable || cutEligible || pseudoBoolean || circuit || constArrayElement ||
                table || nValue || regular || mdd || gccCount || makespanPlans > 0 || diffnPlans > 0 ||
                arrayMinMax || product
        // An explicit objective-cone request drops the disjunctive big-M rows and every variable
        // disconnected from the objective, so it always fits the cap even when the full model is over it.
        val coneRequested = base.objectiveCone
        val baseFits = coneRequested || cells <= baseCap
        // LP runs whenever the model is structurally amenable, the emphasis permits it, and the base
        // relaxation fits the ceiling (a cone request always fits).
        val lpActive = structApplicable && config.resolved(LpTechnique.BOUNDING) &&
            (coneRequested || cells <= ceilingCap)

        // A small base relaxation budgets its hulls against the base cap; a larger (but in-ceiling) base
        // budgets against the ceiling, since its per-node LP is already that size.
        val makespanLp = lpActive && makespanPlans > 0
        val diffnLp = lpActive && diffnPlans > 0
        val hullCap = if (baseFits) baseCap else ceilingCap

        // Structurally-present hulls compete for the budget once admitted (a non-admitted hull is never
        // built, so it costs nothing). Each estimate sums over its factors and honours that hull's own
        // MAX_* cap, exactly as the builder does. [hullAdmitted] gates by emphasis: AGGRESSIVE admits
        // every hull, DEFAULT admits only the small ones (the middle tier), and an override wins either
        // way — the global budget still caps whatever is admitted.
        val candidates = if (!lpActive) {
            emptyList()
        } else {
            buildList {
                fun admit(e: HullEstimate?) = e?.let { if (hullAdmitted(config, it)) add(it) }
                // The whole-table root-box reading copies, so it is materialized on the first family
                // that needs it and shared from there — never per family, and never on a model
                // carrying no hull factor at all.
                var boxes: RootBoxes? = null
                suspend fun SequenceScope<Unit>.rootBoxes(): RootBoxes =
                    boxes ?: prepareRootBoxes(problem).also { boxes = it }
                // Per-factor hull sizes come from each factor's LP hull estimate (single source with
                // the build); the driver-emitted circuit arcs and time-indexed model keep their own.
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

        // Tableau eligibility belongs to the assembled model's exact columns and row data;
        // source mapping discharges rounding bounds and inherited guards before sharing a cut.
        val cuts = lpActive && config.resolved(LpTechnique.CUTS)
        val structuralCuts = cuts && (cutEligible || pseudoBoolean)
        val energetic = cumulative && config.resolved(LpTechnique.ENERGETIC)
        var plan = base.copy(
            bounding = base.bounding || lpActive,
            cuts = base.cuts || cuts,
            // LP learning rides on the bounding path: the objective-bound reason is built from the
            // exact basis-certificate the sparse solve already computes.
            learn = base.learn || lpActive,
            // The LP-rounding probe solves the root relaxation through the sparse revised simplex.
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
            // Boolean RLT is a no-op without 0/1 knapsack rows.
            booleanRlt = base.booleanRlt || (structuralCuts && config.resolved(LpTechnique.BOOLEAN_RLT)),
            lagrangian = base.lagrangian || (allDifferent && config.resolved(LpTechnique.LAGRANGIAN)),
            energeticReasoning = base.energeticReasoning || energetic,
            // Derive the cadence only when the auto path is the one enabling the check — an
            // explicit caller enablement keeps the caller's cadence untouched.
            energeticEvery = if (energetic && !base.energeticReasoning) {
                maxOf(base.energeticEvery.toLong(), 1L + (energeticOps - 1L) / ENERGETIC_OPS_PER_CHECK)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            } else {
                base.energeticEvery
            },
        )
        val sparse = if (config.resolved(LpTechnique.SPARSE_TRIAL) && !base.bounding &&
            structApplicable && config.resolved(LpTechnique.BOUNDING)
        ) {
            if (sparseTrialAllowed) prepareSparseLpAdmission(problem, objective, cancellation,
                minOf(sparseTrialWork, base.rootMaxWork.takeIf { it > 0L } ?: SPARSE_LP_MAX_WORK))
            else SparseLpAdmission(mapOf("DeclineProjection" to 1L))
        } else null
        if (sparse?.admitted == true) {
            plan = LpPlan(
                bounding = true, boundMaxDepth = 0, learn = true, probe = false, rootCutHarvest = false,
                rootMaxWork = minOf(base.rootMaxWork.takeIf { it > 0L } ?: SPARSE_LP_MAX_WORK, SPARSE_LP_MAX_WORK),
                rootBudgetFraction = base.rootBudgetFraction, rootBudgetMillis = base.rootBudgetMillis,
                lpWorkBudgetFraction = base.lpWorkBudgetFraction, boundMaxPivots = base.boundMaxPivots,
                boundEvery = base.boundEvery, warmStart = base.warmStart, branching = base.branching,
                componentOwnership = base.componentOwnership,
            )
        }
        observe?.invoke(LpAdmission(rows, baseCols, cells, structApplicable,
            config.resolved(LpTechnique.BOUNDING), coneRequested || cells <= ceilingCap, base.bounding, plan,
            if (base.element) null else selection.elementIndexes.toSet(), sparse))
        return plan
    }

    /** Relaxation-size proxy `m × (n + m + 1)` for `rows = m` constraint rows and `cols = n`
     *  structural columns (plus the `m` slacks and the rhs column). */
    private fun tableauCells(rows: Long, cols: Long): Long = rows * (cols + rows + 1L)

    /** A gated hull's estimated added columns and rows, summed over its factors of one kind. */
    private class HullEstimate(
        val key: LpTechnique,
        val cols: Long,
        val rows: Long,
        val elementIndex: Int? = null,
    )

    private class HullSelection(val techniques: Set<LpTechnique>, val elementIndexes: Set<Int>)

    /** Whether [h]'s hull may compete for the size budget under [config]: an explicit override wins;
     *  else the technique's cost tier must be permitted by the emphasis (`AGGRESSIVE`), or — the middle
     *  tier — the per-node simplex is on ([LpTiming.MEDIUM] permitted) and the hull is small enough
     *  ([SMALL_HULL_DIM]). */
    private fun hullAdmitted(config: LpConfig, h: HullEstimate): Boolean {
        config.overrides[h.key]?.let { return it }
        if (h.key.timing in config.emphasis.timings) return true
        return LpTiming.MEDIUM in config.emphasis.timings && h.cols + h.rows <= SMALL_HULL_DIM
    }

    /** Accept hulls smallest-first while the combined `base + accepted` size stays under [maxCells]
     *  (the hull budget: the base cap or the ceiling); the rest are shed (their flag stays off),
     *  so a stack of hulls can't push the per-node LP past the budget. */
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

    /** Arc-model columns (`Σ` candidate arcs) + degree/channel rows over the under-cap [Circuit]
     *  factors (mirrors `CpToLpRelaxation.buildArcModel`; subcircuit keeps self-loop arcs). */
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
            // Mirrors the build's own decline: an open-sided successor gets no arc model, so its arcs
            // must not enter the estimate either.
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

    /** The aggregate hull size of every factor in the [family] convex-hull family, summing each factor's
     *  own LP hull estimate — the single source shared with the build, so
     *  the gating estimate cannot drift from the rows actually emitted. */
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

    /** Time-indexed `x_{i,t}` columns + resource/assignment/channel rows over the bounded-horizon
     *  scheduling factors, honouring the horizon / column caps (mirrors `buildCumulativeTimeIndexed`). */
    private suspend fun SequenceScope<Unit>.timeIndexedEstimate(problem: Problem): HullEstimate? {
        var visits = 0
        var cols = 0L
        var rows = 0L
        var any = false
        for (v in prepareSchedulingViews(problem)) {
            if (++visits % CONSTRUCTION_BATCH_SIZE == 0) yield(Unit)
            // Mirrors the build's own decline: a view with an open-sided start gets no time-indexed
            // model, so its columns must not enter the estimate either.
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
