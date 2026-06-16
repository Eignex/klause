package com.eignex.klause.solver.backtrack

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Disjunctive
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.lp.CpToLpRelaxation
import com.eignex.klause.solver.lp.CumulativeEnergeticBound
import com.eignex.klause.solver.lp.CumulativeRelaxation
import com.eignex.klause.solver.lp.schedulingViews

/**
 * Structural auto-configuration of the LP-relaxation family. Each technique is enabled when —
 * and only when — the problem actually contains the structure it targets. Applicability is a
 * structural fact, so this never *speculates* about performance; it just stops the LP machinery
 * from being a manual per-instance decision:
 *
 *  - **[BacktrackParams.lpBounding]** — there is genuine integer-linear structure ([Linear] /
 *    [ReifiedLinear]) the relaxation can exploit, or a global whose relaxation/cuts need the LP
 *    ([AllDifferent], [GlobalCardinality], [Circuit], constant-array [Element], [Table]). With it
 *    come the bounding-stack techniques that need no extra structure: the per-node objective
 *    propagation ([BacktrackParams.lpObjectiveBound]), Farkas learning ([BacktrackParams.lpLearn]),
 *    the LP↔propagation fixpoint ([BacktrackParams.lpFixpoint]) and the LP-rounding incumbent
 *    probe ([BacktrackParams.lpProbe]).
 *  - **[BacktrackParams.lpCuts]** — an [AllDifferent] (Hall / assignment cuts),
 *    [GlobalCardinality] (occurrence sum cuts), or [PseudoBoolean] (knapsack cover cuts) is
 *    present; the persistent root pool ([BacktrackParams.lpCutPool]) rides along.
 *  - **[BacktrackParams.lpCircuit]** — a [Circuit] is present (subtour-elimination cuts).
 *  - **[BacktrackParams.lpElement]** — a constant-array [Element] is present (its convex hull).
 *  - **[BacktrackParams.lpTable]** — a [Table] is present (its convex hull).
 *  - **[BacktrackParams.lpNValue]** — an [NValue] is present (its one-hot value hull, #435).
 *  - **[BacktrackParams.lagrangian]** — an [AllDifferent] is present (the weighted-assignment bound).
 *  - **[BacktrackParams.energeticReasoning]** — a [Cumulative] is present. When the auto path is
 *    the one enabling it, [BacktrackParams.energeticEvery] is derived from the models' task counts
 *    so the O(windows² · tasks) window scan stays a bounded fraction of a node's cost (a per-check
 *    budget normalization, [ENERGETIC_OPS_PER_CHECK] — the same class of guard as the tableau
 *    cap, not a tuning judgement); a caller who enabled the check explicitly keeps their cadence.
 *  - **[BacktrackParams.lpCumulative]** — a [Cumulative] / [Disjunctive] with a *verifiable*
 *    makespan variable is present (the energetic makespan LP row, #430). Applicability is the
 *    structural fact that [CumulativeRelaxation] proves a makespan link, so this also lets the
 *    scheduling globals turn the LP bounding stack on; size-gated like the other relaxation flags.
 *
 * The LP-relaxation flags are additionally gated by a **size guard**: the dual simplex keeps a
 * dense `m × (n + m + 1)` Long tableau per node, so the auto path declines models whose estimated
 * tableau exceeds [KlauseConfig.lpMaxTableauCells] (a memory/feasibility bound, not a tuning
 * judgement — the engine is purpose-built for small dense per-node LPs; raise the cap via its env
 * once a sparser solve can afford bigger relaxations). The estimate folds in each enabled
 * gated hull's columns/rows (circuit / element / table / nvalue / time-indexed) and accepts them
 * smallest-first under the budget, so a stack of hulls is shed rather than allowed to defeat the
 * guard (#484). The Lagrangian and energetic bounds have their own internal caps and are not
 * size-gated here. An explicit caller flag bypasses the guard: every flag is OR-ed onto `base`, so
 * an explicit setting is never turned *off*.
 *
 * Called by `BacktrackSolver` under [BacktrackParams.lpConfig] (via [resolve]); also callable
 * directly for ahead-of-time configuration (the bench's auto mode).
 */
object LpAutoConfig {

    /**
     * Per-check operation budget the auto-derived [BacktrackParams.energeticEvery] normalises to:
     * `cadence = ceil(Σ tasksᵢ³ / budget)` over the Cumulatives the scan actually visits (factors
     * past the bound's own task cap are skipped there and cost nothing). A 48-task model stays at
     * cadence 1; a 256-task model lands around 128.
     */
    const val ENERGETIC_OPS_PER_CHECK: Long = 1L shl 17

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

                is GlobalCardinality -> globalCardinality = true

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

                is Element -> if (!f.arrIsVars) constArrayElement = true

                is Table -> table = true

                is NValue -> nValue = true

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
        val baseCols = problem.numIntVars.toLong() + problem.numBoolVars.toLong()
        // Cost guard: the configurable dense-tableau ceiling (env-tunable via KlauseConfig).
        val maxCells = KlauseConfig.current.lpMaxTableauCells
        val baseFits = tableauCells(rows, baseCols) <= maxCells

        val cutEligible = allDifferent || globalCardinality
        val makespanLp = baseFits && makespanPlans > 0
        // Structural LP-amenability, independent of the size guard.
        val structApplicable =
            lpEmittable || cutEligible || pseudoBoolean || circuit || constArrayElement ||
                table || nValue || makespanPlans > 0
        // The simplex (MEDIUM) underlies every relaxation row, so the EXHAUSTIVE hulls additionally
        // require it — guaranteed by the tier nesting (EXHAUSTIVE ⊇ MEDIUM).
        // #571: an explicit objective-cone request always fits the dense cap (the cone drops the
        // disjunctive big-M rows and every variable disconnected from the objective), so it bounds on
        // the dense path even when the *full* model is over the cap — and it must not be diverted to
        // the sparse-primary pipeline (which would build the full relaxation instead).
        val coneRequested = base.lpObjectiveCone
        val boundingApplicable = (baseFits || coneRequested) && structApplicable
        val bounding = boundingApplicable && config.resolved(LpTechnique.BOUNDING)
        // Over the dense cap but within the sparse cap: route to the bound-only sparse pipeline (#602)
        // instead of disabling LP. The sparse path skips the dense tableau the guard protects against.
        val sparsePrimary = !coneRequested && !baseFits && structApplicable &&
            config.resolved(LpTechnique.BOUNDING) &&
            tableauCells(rows, baseCols) <= KlauseConfig.current.lpSparseMaxTableauCells

        // Only structurally-present hulls the emphasis permits compete for the budget (a forbidden
        // hull is never built, so it costs nothing). Each estimate sums over its factors and honours
        // that hull's own MAX_* cap, exactly as the builder does.
        val candidates = if (!bounding) {
            emptyList()
        } else {
            buildList {
                if (circuit && config.resolved(LpTechnique.CIRCUIT)) circuitEstimate(problem)?.let(::add)
                if (constArrayElement && config.resolved(LpTechnique.ELEMENT)) elementEstimate(problem)?.let(::add)
                if (table && config.resolved(LpTechnique.TABLE)) tableEstimate(problem)?.let(::add)
                if (nValue && config.resolved(LpTechnique.NVALUE)) nValueEstimate(problem)?.let(::add)
                if (scheduling && config.resolved(LpTechnique.CUMULATIVE_TIME_INDEXED)) {
                    timeIndexedEstimate(problem)?.let(::add)
                }
            }
        }
        val acceptedHulls = acceptUnderBudget(rows, baseCols, candidates, maxCells)

        val cuts = bounding && (cutEligible || pseudoBoolean) && config.resolved(LpTechnique.CUTS)
        val energetic = cumulative && config.resolved(LpTechnique.ENERGETIC)
        return base.copy(
            lpBounding = base.lpBounding || bounding || sparsePrimary,
            lpSparseBound = base.lpSparseBound || bounding || sparsePrimary,
            lpSparsePrimary = base.lpSparsePrimary || sparsePrimary,
            lpCuts = base.lpCuts || cuts,
            lpCutPool = base.lpCutPool || cuts,
            lpLearn = base.lpLearn || bounding,
            lpObjectiveBound = base.lpObjectiveBound || bounding,
            lpFixpoint = base.lpFixpoint || bounding,
            lpProbe = base.lpProbe || bounding,
            lpCircuit = base.lpCircuit || (LpTechnique.CIRCUIT in acceptedHulls),
            lpElement = base.lpElement || (LpTechnique.ELEMENT in acceptedHulls),
            lpTable = base.lpTable || (LpTechnique.TABLE in acceptedHulls),
            lpNValue = base.lpNValue || (LpTechnique.NVALUE in acceptedHulls),
            lpCumulative = base.lpCumulative || (bounding && makespanLp),
            lpCumulativeTimeIndexed = base.lpCumulativeTimeIndexed ||
                (LpTechnique.CUMULATIVE_TIME_INDEXED in acceptedHulls),
            lpCumulativeFlow = base.lpCumulativeFlow || (scheduling && config.resolved(LpTechnique.CUMULATIVE_FLOW)),
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
    }

    /** Dense-tableau cell count `m × (n + m + 1)` for `rows = m` constraint rows and `cols = n`
     *  structural columns (plus the `m` slacks and the rhs column). */
    private fun tableauCells(rows: Long, cols: Long): Long = rows * (cols + rows + 1L)

    /** A gated hull's estimated added columns and rows, summed over its factors of one kind. */
    private class HullEstimate(val key: LpTechnique, val cols: Long, val rows: Long)

    /** Accept hulls smallest-first while the combined `base + accepted` tableau stays under
     *  [maxCells] (the configurable [KlauseConfig.lpMaxTableauCells]); the rest are shed (their flag
     *  stays off), so a stack of hulls can't push the per-node LP past the budget (#484). */
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
                problem.intDomains[succ[i]].forEach { j -> if ((selfLoops || j != i) && j in 0 until n) arcs++ }
            }
            if (arcs == 0L || arcs > CpToLpRelaxation.MAX_CIRCUIT_ARCS) continue
            any = true
            cols += arcs
            rows += 3L * n // out-degree + channel + in-degree rows (upper bound)
        }
        return if (any) HullEstimate(LpTechnique.CIRCUIT, cols, rows) else null
    }

    /** Constant-array [Element] selector columns + 3 rows each (mirrors `buildElementHull`). */
    private fun elementEstimate(problem: Problem): HullEstimate? {
        var cols = 0L
        var rows = 0L
        var any = false
        for (f in problem.factors) {
            if (f !is Element || f.arrIsVars || f.arr.size > CpToLpRelaxation.MAX_ELEM) continue
            val declared = problem.intDomains[f.idx]
            var k = 0L
            for (p in f.arr.indices) if ((p + f.indexOffset) in declared) k++
            if (k == 0L) continue
            any = true
            cols += k
            rows += 3L // Σ y = 1 + index channel + result channel
        }
        return if (any) HullEstimate(LpTechnique.ELEMENT, cols, rows) else null
    }

    /** [Table] selector columns (≤ tuple count) + `1 + arity` rows each (mirrors `buildTableHull`). */
    private fun tableEstimate(problem: Problem): HullEstimate? {
        var cols = 0L
        var rows = 0L
        var any = false
        for (f in problem.factors) {
            if (f !is Table || f.numTuples > CpToLpRelaxation.MAX_TUPLES) continue
            any = true
            cols += f.numTuples.toLong() // upper bound on the declared-feasible selectors
            rows += 1L + f.arity // Σ y = 1 + one channel per column
        }
        return if (any) HullEstimate(LpTechnique.TABLE, cols, rows) else null
    }

    /** [NValue] one-hot columns (`z` per var×value + `y` per value) and rows (mirrors `buildNValueHull`). */
    private fun nValueEstimate(problem: Problem): HullEstimate? {
        var cols = 0L
        var rows = 0L
        var any = false
        for (f in problem.factors) {
            if (f !is NValue || f.presents.isNotEmpty()) continue
            var cells = 0L
            for (x in f.xs) cells += problem.intDomains[x].size.toLong()
            if (cells == 0L || cells > CpToLpRelaxation.MAX_NVALUE_CELLS) continue
            any = true
            cols += 2L * cells // z (per var×value) + y (≤ distinct values ≤ cells)
            rows += cells + 2L * f.xs.size + 1L // y≥z rows + (Σz=1, channel) per var + the count row
        }
        return if (any) HullEstimate(LpTechnique.NVALUE, cols, rows) else null
    }

    /** Time-indexed `x_{i,t}` columns + resource/assignment/channel rows over the bounded-horizon
     *  scheduling factors (#453), honouring the horizon / column caps (mirrors `buildCumulativeTimeIndexed`). */
    private fun timeIndexedEstimate(problem: Problem): HullEstimate? {
        var cols = 0L
        var rows = 0L
        var any = false
        for (v in schedulingViews(problem)) {
            val n = v.starts.size
            var t0 = Int.MAX_VALUE
            var t1 = Int.MIN_VALUE
            var c = 0L
            var ok = true
            for (i in 0 until n) {
                val dom = problem.intDomains[v.starts[i]]
                if (dom.max < dom.min) {
                    ok = false
                    break
                }
                if (dom.min < t0) t0 = dom.min
                val end = dom.max.toLong() + v.durations[i]
                if (end > t1) t1 = end.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                c += (dom.max - dom.min + 1).toLong()
            }
            val horizon = t1.toLong() - t0
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
