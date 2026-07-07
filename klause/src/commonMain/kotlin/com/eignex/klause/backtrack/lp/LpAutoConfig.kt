package com.eignex.klause.backtrack.lp

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.ArrayMinMax
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Product
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.factor.circuit.Circuit
import com.eignex.klause.factor.circuit.Subcircuit
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.GlobalCardinality
import com.eignex.klause.factor.global.NValue
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.scheduling.Diffn
import com.eignex.klause.factor.scheduling.Disjunctive
import com.eignex.klause.factor.table.Element
import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Regular
import com.eignex.klause.factor.table.Table
import com.eignex.klause.lp.bound.CumulativeEnergeticBound
import com.eignex.klause.lp.relaxation.CpToLpRelaxation
import com.eignex.klause.lp.relaxation.CumulativeRelaxation
import com.eignex.klause.lp.relaxation.schedulingViews
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem

/**
 * Structural auto-configuration of the LP-relaxation family. Each technique is enabled when —
 * and only when — the problem actually contains the structure it targets. Applicability is a
 * structural fact, so this never *speculates* about performance; it just stops the LP machinery
 * from being a manual per-instance decision:
 *
 *  - **[LpPlan.bounding]** — there is genuine integer-linear structure ([Linear] /
 *    [ReifiedLinear]) the relaxation can exploit, or a global whose relaxation/cuts need the LP
 *    ([AllDifferent], [GlobalCardinality], [Circuit], constant-array [Element], [Table]). With it
 *    come the bounding-stack techniques that need no extra structure: per-node objective propagation,
 *    Farkas learning ([LpPlan.learn]) and the LP-rounding incumbent probe
 *    ([LpPlan.probe]).
 *  - **[LpPlan.cuts]** — an [AllDifferent] (Hall / assignment cuts),
 *    [GlobalCardinality] (occurrence sum cuts), or [PseudoBoolean] (knapsack cover cuts) is
 *    present; the cuts found are harvested once at the root into a global pool reused at every node.
 *  - **[LpPlan.circuit]** — a [Circuit] is present (subtour-elimination cuts).
 *  - **[LpPlan.element]** — a constant-array [Element] is present (its convex hull).
 *  - **[LpPlan.table]** — a [Table] is present (its convex hull).
 *  - **[LpPlan.nValue]** — an [NValue] is present (its one-hot value hull, #435).
 *  - **[LpPlan.lagrangian]** — an [AllDifferent] is present (the weighted-assignment bound).
 *  - **[LpPlan.energeticReasoning]** — a [Cumulative] is present. When the auto path is
 *    the one enabling it, [LpPlan.energeticEvery] is derived from the models' task counts
 *    so the O(windows² · tasks) window scan stays a bounded fraction of a node's cost (a per-check
 *    budget normalization, [ENERGETIC_OPS_PER_CHECK] — the same class of guard as the tableau
 *    cap, not a tuning judgement); a caller who enabled the check explicitly keeps their cadence.
 *  - **[LpPlan.cumulative]** — a [Cumulative] / [Disjunctive] with a *verifiable*
 *    makespan variable is present (the energetic makespan LP row, #430). Applicability is the
 *    structural fact that [CumulativeRelaxation] proves a makespan link, so this also lets the
 *    scheduling globals turn the LP bounding stack on; size-gated like the other relaxation flags.
 *
 * The LP-relaxation flags are additionally gated by a **size guard** (the sparse revised simplex is the
 * only LP engine, #705; these are per-node solve-cost bounds, not tuning judgements). LP is declined
 * once the estimated relaxation size (`rows × (cols + rows + 1)`) exceeds the ceiling
 * [KlauseConfig.lpCeilingTableauCells]. The gated hulls (circuit / element / table / nvalue /
 * time-indexed) then compete for a hull budget — the base cap [KlauseConfig.lpMaxTableauCells] when the
 * base relaxation fits it, else the ceiling — accepted smallest-first, so a stack of hulls is shed
 * rather than allowed to defeat the guard (#484). The Lagrangian and energetic bounds have their own
 * internal caps and are not size-gated here. An explicit caller flag bypasses the guard: every flag is
 * OR-ed onto `base`, so an explicit setting is never turned *off*.
 *
 * Called by `BacktrackSolver` under [BacktrackParams.lpConfig] (via [resolve]); also callable
 * directly for ahead-of-time configuration (the bench's auto mode).
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
     * trades a little build cost for a tighter bound; the #484 budget still caps the admitted stack.
     */
    const val SMALL_HULL_DIM: Long = 128L

    /** `base` with every structurally-applicable LP technique enabled — i.e. [resolve] at the
     *  [LpEmphasis.AGGRESSIVE] ceiling (no cost gating). The historical structural auto-config. */
    fun recommend(problem: Problem, base: BacktrackParams = BacktrackParams()): BacktrackParams =
        resolve(problem, LpConfig.AGGRESSIVE, base)

    /**
     * `base` with each LP technique enabled where [problem]'s structure makes it applicable **and**
     * [config] permits it (the emphasis cost ceiling + per-technique overrides — see [LpConfig]).
     * Structural applicability and the dense-tableau size guard are unchanged; the emphasis just caps
     * which cost tiers may run, so `AGGRESSIVE` reproduces the old all-applicable [recommend] and the
     * cheaper levels switch the expensive tiers off. Flags are OR-ed onto `base`, so an explicit
     * caller setting is never turned off.
     */
    @Suppress("CyclomaticComplexMethod")
    fun resolve(problem: Problem, config: LpConfig, base: BacktrackParams = BacktrackParams()): BacktrackParams {
        var lpEmittable = false
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
            when (f) {
                is Linear -> {
                    lpEmittable = true
                    rows += 1
                }

                is ReifiedLinear -> {
                    lpEmittable = true
                    rows += 2
                }

                is ReifiedPseudoBoolean -> {
                    lpEmittable = true
                    rows += 2
                }

                is ReifiedCardinality -> {
                    lpEmittable = true
                    rows += 2
                }

                is Cardinality -> rows += 2

                is Clause -> rows += 1

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

                is Disjunctive -> scheduling = true

                is PseudoBoolean -> {
                    pseudoBoolean = true
                    rows += 1
                }

                is Circuit -> circuit = true

                is Subcircuit -> circuit = true

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
        // ── #484 dense-tableau size guard ────────────────────────────────────────────────────────
        // The per-node tableau is the base relaxation PLUS every enabled gated-hull's columns/rows.
        // Estimate each hull against the same MAX_* caps the builders skip at, then accept them
        // smallest-first only while the combined `base + accepted` tableau stays under the budget — so
        // a stack of hulls (Table + NValue + Circuit + time-indexed) can't defeat the guard: the
        // largest are shed and the base LP still runs.
        val makespanPlans = if (scheduling) CumulativeRelaxation(problem).plans.size else 0
        rows += makespanPlans.toLong()
        // Diffn contributes the same kind of makespan row, one per derived axis plan (over an existing
        // column), counted separately so it gates under its own technique rather than lpCumulative.
        val diffnPlans = if (diffn) {
            CumulativeRelaxation(problem, includeCumulative = false, includeDiffn = true).plans.size
        } else {
            0
        }
        rows += diffnPlans.toLong()
        val baseCols = problem.numIntVars.toLong() + problem.numBoolVars.toLong()
        // Two per-node cost-guard tiers (the sparse revised simplex is the only LP engine, #705; both are
        // pure cost guards on solve time, the bound is sound either way). `tableauCells` is a size proxy,
        // not a literal allocation. The base cap bounds the hull budget of a small base relaxation; the
        // ceiling is the absolute size past which LP is declined.
        val baseCap = KlauseConfig.current.lpMaxTableauCells
        val ceilingCap = KlauseConfig.current.lpCeilingTableauCells
        val cells = tableauCells(rows, baseCols)

        val cutEligible = allDifferent || globalCardinality
        // Structural LP-amenability, independent of the size guard.
        val structApplicable =
            lpEmittable || cutEligible || pseudoBoolean || circuit || constArrayElement ||
                table || nValue || regular || mdd || gccCount || makespanPlans > 0 || diffnPlans > 0 ||
                arrayMinMax || product
        // #571: an explicit objective-cone request drops the disjunctive big-M rows and every variable
        // disconnected from the objective, so it always fits the cap even when the full model is over it.
        val coneRequested = base.lpPlan.objectiveCone
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
                // Per-factor hull sizes come from each factor's Linearizer.sizeEstimate (single source with
                // the build); the driver-emitted circuit arcs and time-indexed model keep their own.
                if (circuit) admit(circuitEstimate(problem))
                if (constArrayElement) admit(hullEstimate(problem, LpTechnique.ELEMENT) { it is Element })
                if (table) admit(hullEstimate(problem, LpTechnique.TABLE) { it is Table })
                if (nValue) admit(hullEstimate(problem, LpTechnique.NVALUE) { it is NValue })
                if (regular) admit(hullEstimate(problem, LpTechnique.REGULAR) { it is Regular })
                if (mdd) admit(hullEstimate(problem, LpTechnique.MDD) { it is Mdd })
                if (gccCount) admit(hullEstimate(problem, LpTechnique.GCC_COUNT) { it is GlobalCardinality })
                if (scheduling) admit(timeIndexedEstimate(problem))
            }
        }
        val acceptedHulls = acceptUnderBudget(rows, baseCols, candidates, hullCap)

        // Cuts run whenever LP bounding is active (#705): the structural separators read the LP point
        // through the revised simplex. Cut-eligible structure required.
        val cuts = lpActive && (cutEligible || pseudoBoolean) && config.resolved(LpTechnique.CUTS)
        val energetic = cumulative && config.resolved(LpTechnique.ENERGETIC)
        return base.copy(
            lpPlan = base.lpPlan.copy(
                bounding = base.lpPlan.bounding || lpActive,
                cuts = base.lpPlan.cuts || cuts,
                // LP learning rides on the bounding path: the objective-bound reason is built from the
                // exact basis-certificate the sparse solve already computes.
                learn = base.lpPlan.learn || lpActive,
                // The LP-rounding probe solves the root relaxation through the sparse revised simplex.
                probe = base.lpPlan.probe || lpActive,
                circuit = base.lpPlan.circuit || (LpTechnique.CIRCUIT in acceptedHulls),
                element = base.lpPlan.element || (LpTechnique.ELEMENT in acceptedHulls),
                table = base.lpPlan.table || (LpTechnique.TABLE in acceptedHulls),
                nValue = base.lpPlan.nValue || (LpTechnique.NVALUE in acceptedHulls),
                regular = base.lpPlan.regular || (LpTechnique.REGULAR in acceptedHulls),
                mdd = base.lpPlan.mdd || (LpTechnique.MDD in acceptedHulls),
                gccCount = base.lpPlan.gccCount || (LpTechnique.GCC_COUNT in acceptedHulls),
                cumulative = base.lpPlan.cumulative || makespanLp,
                diffn = base.lpPlan.diffn || (diffnLp && config.resolved(LpTechnique.DIFFN)),
                cumulativeTimeIndexed = base.lpPlan.cumulativeTimeIndexed ||
                    (LpTechnique.CUMULATIVE_TIME_INDEXED in acceptedHulls),
                cumulativeFlow = base.lpPlan.cumulativeFlow ||
                    (scheduling && config.resolved(LpTechnique.CUMULATIVE_FLOW)),
                // ArrayMinMax tight face and Product McCormick: EXHAUSTIVE-tier relaxations,
                // on when their structure is present and the emphasis permits them.
                linMaxTightFace = base.lpPlan.linMaxTightFace ||
                    (lpActive && arrayMinMax && config.resolved(LpTechnique.LINMAX_TIGHT)),
                productMcCormick = base.lpPlan.productMcCormick ||
                    (lpActive && product && config.resolved(LpTechnique.PRODUCT_MCCORMICK)),
                // Implied-bound cuts: a cut family, so requires cuts active + Boolean structure.
                impliedBoundCuts = base.lpPlan.impliedBoundCuts ||
                    (cuts && problem.numBoolVars > 0 && config.resolved(LpTechnique.IMPLIED_BOUND)),
                flowCoverCuts = base.lpPlan.flowCoverCuts || (cuts && config.resolved(LpTechnique.FLOW_COVER)),
                // Boolean RLT: relaxation strengthening; a no-op without 0/1 knapsack rows.
                booleanRlt = base.lpPlan.booleanRlt || (cuts && config.resolved(LpTechnique.BOOLEAN_RLT)),
                lagrangian = base.lpPlan.lagrangian || (allDifferent && config.resolved(LpTechnique.LAGRANGIAN)),
                energeticReasoning = base.lpPlan.energeticReasoning || energetic,
                // Derive the cadence only when the auto path is the one enabling the check — an
                // explicit caller enablement keeps the caller's cadence untouched.
                energeticEvery = if (energetic && !base.lpPlan.energeticReasoning) {
                    maxOf(base.lpPlan.energeticEvery.toLong(), 1L + (energeticOps - 1L) / ENERGETIC_OPS_PER_CHECK)
                        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                } else {
                    base.lpPlan.energeticEvery
                },
            ),
        )
    }

    /** Relaxation-size proxy `m × (n + m + 1)` for `rows = m` constraint rows and `cols = n`
     *  structural columns (plus the `m` slacks and the rhs column). */
    private fun tableauCells(rows: Long, cols: Long): Long = rows * (cols + rows + 1L)

    /** A gated hull's estimated added columns and rows, summed over its factors of one kind. */
    private class HullEstimate(val key: LpTechnique, val cols: Long, val rows: Long)

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
     *  (the configurable [KlauseConfig.lpMaxTableauCells]); the rest are shed (their flag stays off),
     *  so a stack of hulls can't push the per-node LP past the budget (#484). */
    private fun acceptUnderBudget(
        baseRows: Long,
        baseCols: Long,
        candidates: List<HullEstimate>,
        maxCells: Long,
    ): Set<LpTechnique> {
        var r = baseRows
        var c = baseCols
        val accepted = HashSet<LpTechnique>()
        for (h in candidates.sortedBy { it.cols + it.rows }) {
            if (tableauCells(r + h.rows, c + h.cols) <= maxCells) {
                r += h.rows
                c += h.cols
                accepted.add(h.key)
            }
        }
        return accepted
    }

    /** Arc-model columns (`Σ` candidate arcs) + degree/channel rows over the under-cap [Circuit] /
     *  [Subcircuit] factors (mirrors `CpToLpRelaxation.buildArcModel`; subcircuit keeps self-loop arcs). */
    private fun circuitEstimate(problem: Problem): HullEstimate? {
        var cols = 0L
        var rows = 0L
        var any = false
        for (f in problem.factors) {
            val succ = when (f) {
                is Circuit -> f.succ
                is Subcircuit -> f.succ
                else -> continue
            }
            val selfLoops = f is Subcircuit
            val n = succ.size
            if (n < 2) continue
            var arcs = 0L
            for (i in 0 until n) {
                problem.intDomains[succ[i]].forEach { j ->
                    if ((selfLoops || j != i.toLong()) &&
                        j in 0L until n
                    ) {
                        arcs++
                    }
                }
            }
            if (arcs == 0L || arcs > CpToLpRelaxation.MAX_CIRCUIT_ARCS) continue
            any = true
            cols += arcs
            rows += 3L * n // out-degree + channel + in-degree rows (upper bound)
        }
        return if (any) HullEstimate(LpTechnique.CIRCUIT, cols, rows) else null
    }

    /** The aggregate hull size of every factor matching [applies] (one per-factor convex-hull family),
     *  summing each factor's own [com.eignex.klause.lp.Linearizer.sizeEstimate] — the single source
     *  shared with the build, so the gating estimate cannot drift from the rows actually emitted. */
    private fun hullEstimate(problem: Problem, technique: LpTechnique, applies: (Factor) -> Boolean): HullEstimate? {
        var cols = 0L
        var rows = 0L
        var any = false
        for (f in problem.factors) {
            if (!applies(f)) continue
            val e = f.asLinearizer().sizeEstimate(problem.intDomains) ?: continue
            cols += e.cols
            rows += e.rows
            any = true
        }
        return if (any) HullEstimate(technique, cols, rows) else null
    }

    /** Time-indexed `x_{i,t}` columns + resource/assignment/channel rows over the bounded-horizon
     *  scheduling factors (#453), honouring the horizon / column caps (mirrors `buildCumulativeTimeIndexed`). */
    private fun timeIndexedEstimate(problem: Problem): HullEstimate? {
        var cols = 0L
        var rows = 0L
        var any = false
        for (v in schedulingViews(problem)) {
            val n = v.starts.size
            var t0 = Long.MAX_VALUE
            var t1 = Long.MIN_VALUE
            var c = 0L
            var ok = true
            for (i in 0 until n) {
                val dom = problem.intDomains[v.starts[i]]
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
