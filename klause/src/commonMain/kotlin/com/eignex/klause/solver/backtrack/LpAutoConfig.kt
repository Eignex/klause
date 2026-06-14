package com.eignex.klause.solver.backtrack

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
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Table
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
 * tableau exceeds [MAX_AUTO_TABLEAU_CELLS] (a memory/feasibility bound, not a tuning judgement —
 * the engine is purpose-built for small dense per-node LPs). The Lagrangian and energetic bounds
 * have their own internal caps and are not size-gated here. An explicit caller flag bypasses the
 * guard: every flag is OR-ed onto `base`, so an explicit setting is never turned *off*.
 *
 * Called by `BacktrackSolver` under [BacktrackParams.lpConfig] (via [resolve]); also callable
 * directly for ahead-of-time configuration (the bench's auto mode).
 */
object LpAutoConfig {

    /**
     * Auto-enable ceiling on the estimated dense-tableau size `rows × (cols + rows + 1)`, in Long
     * cells (8 bytes each — the cap is ~8 MB per node LP). The row estimate counts the base
     * relaxation's factor rows; cut rows and the gated hull columns are bounded by their own caps.
     */
    const val MAX_AUTO_TABLEAU_CELLS: Long = 1L shl 20

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

                is Element -> if (!f.arrIsVars) constArrayElement = true

                is Table -> table = true

                is NValue -> nValue = true

                else -> Unit
            }
        }
        // The scheduling makespan rows (#430) are one per verified plan; count them before the size
        // guard. The plan build also tells us whether *any* makespan link is provable at all.
        val makespanPlans = if (scheduling) CumulativeRelaxation(problem).plans.size else 0
        rows += makespanPlans.toLong()
        // Time-indexed reformulation (#453): O(n·H) extra columns + H resource rows per bounded-horizon
        // scheduling factor. Estimate them so the auto path only turns it on when the dense tableau
        // still fits; the builder applies the real per-factor horizon / column gates.
        val ti = if (scheduling) timeIndexedEstimate(problem) else TimeIndexedEstimate(0L, 0L, false)
        val cols = problem.numIntVars.toLong() + problem.numBoolVars.toLong() + ti.cols
        val rowsWithTi = rows + ti.rows
        val lpFits = rows * (cols + rows + 1L) <= MAX_AUTO_TABLEAU_CELLS
        val timeIndexedFits = ti.anyFits && rowsWithTi * (cols + rowsWithTi + 1L) <= MAX_AUTO_TABLEAU_CELLS
        val cutEligible = allDifferent || globalCardinality
        val makespanLp = lpFits && makespanPlans > 0

        // Each technique runs iff structurally applicable AND the config permits its cost tier. The
        // simplex (MEDIUM) underlies every relaxation row, so the EXHAUSTIVE add-ons additionally
        // require it — guaranteed by the tier nesting (EXHAUSTIVE ⊇ MEDIUM), so `bounding` is on
        // whenever a higher tier is permitted and applicable.
        val boundingApplicable = lpFits &&
            (
                lpEmittable || cutEligible || pseudoBoolean || circuit || constArrayElement ||
                    table || nValue || makespanLp
                )
        val bounding = boundingApplicable && config.resolved(LpTechnique.BOUNDING)
        val cuts = bounding && (cutEligible || pseudoBoolean) && config.resolved(LpTechnique.CUTS)
        val energetic = cumulative && config.resolved(LpTechnique.ENERGETIC)
        return base.copy(
            lpBounding = base.lpBounding || bounding,
            lpCuts = base.lpCuts || cuts,
            lpCutPool = base.lpCutPool || cuts,
            lpLearn = base.lpLearn || bounding,
            lpObjectiveBound = base.lpObjectiveBound || bounding,
            lpFixpoint = base.lpFixpoint || bounding,
            lpProbe = base.lpProbe || bounding,
            lpCircuit = base.lpCircuit || (bounding && circuit && config.resolved(LpTechnique.CIRCUIT)),
            lpElement = base.lpElement || (bounding && constArrayElement && config.resolved(LpTechnique.ELEMENT)),
            lpTable = base.lpTable || (bounding && table && config.resolved(LpTechnique.TABLE)),
            lpNValue = base.lpNValue || (bounding && nValue && config.resolved(LpTechnique.NVALUE)),
            lpCumulative = base.lpCumulative || (bounding && makespanLp),
            lpCumulativeTimeIndexed = base.lpCumulativeTimeIndexed ||
                (bounding && timeIndexedFits && config.resolved(LpTechnique.CUMULATIVE_TIME_INDEXED)),
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

    /** Above this horizon / column count the time-indexed model is skipped (mirrors the builder gates). */
    private const val MAX_TI_HORIZON: Int = 512
    private const val MAX_TI_COLS: Long = 4096L

    private class TimeIndexedEstimate(val cols: Long, val rows: Long, val anyFits: Boolean)

    /** Estimated added columns/rows of the time-indexed reformulation over the bounded-horizon
     *  scheduling factors, and whether any factor is small enough to encode. */
    private fun timeIndexedEstimate(problem: Problem): TimeIndexedEstimate {
        var cols = 0L
        var rows = 0L
        var anyFits = false
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
            if (!ok || horizon <= 0 || horizon > MAX_TI_HORIZON || c > MAX_TI_COLS) continue
            anyFits = true
            cols += c
            rows += horizon + 2L * n // H resource rows + assignment + channel per task
        }
        return TimeIndexedEstimate(cols, rows, anyFits)
    }
}
