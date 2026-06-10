package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Table

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
 *  - **[BacktrackParams.lagrangian]** — an [AllDifferent] is present (the weighted-assignment bound).
 *  - **[BacktrackParams.energeticReasoning]** — a [Cumulative] is present.
 *
 * The LP-relaxation flags are additionally gated by a **size guard**: the dual simplex keeps a
 * dense `m × (n + m + 1)` Long tableau per node, so the auto path declines models whose estimated
 * tableau exceeds [MAX_AUTO_TABLEAU_CELLS] (a memory/feasibility bound, not a tuning judgement —
 * the engine is purpose-built for small dense per-node LPs). The Lagrangian and energetic bounds
 * have their own internal caps and are not size-gated here. An explicit caller flag bypasses the
 * guard: every flag is OR-ed onto `base`, so an explicit setting is never turned *off*.
 *
 * Called by `BacktrackSolver.improvements` under [BacktrackParams.lpAuto]; also callable directly
 * for ahead-of-time configuration (the bench's auto mode).
 */
object LpAutoConfig {

    /**
     * Auto-enable ceiling on the estimated dense-tableau size `rows × (cols + rows + 1)`, in Long
     * cells (8 bytes each — the cap is ~8 MB per node LP). The row estimate counts the base
     * relaxation's factor rows; cut rows and the gated hull columns are bounded by their own caps.
     */
    const val MAX_AUTO_TABLEAU_CELLS: Long = 1L shl 20

    /** `base` with each LP-family flag enabled where [problem]'s structure makes it applicable. */
    fun recommend(problem: Problem, base: BacktrackParams = BacktrackParams()): BacktrackParams {
        var lpEmittable = false
        var allDifferent = false
        var globalCardinality = false
        var cumulative = false
        var pseudoBoolean = false
        var circuit = false
        var constArrayElement = false
        var table = false
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

                is Cumulative -> cumulative = true

                is PseudoBoolean -> {
                    pseudoBoolean = true
                    rows += 1
                }

                is Circuit -> circuit = true

                is Element -> if (!f.arrIsVars) constArrayElement = true

                is Table -> table = true

                else -> Unit
            }
        }
        val cols = problem.numIntVars.toLong() + problem.numBoolVars.toLong()
        val lpFits = rows * (cols + rows + 1L) <= MAX_AUTO_TABLEAU_CELLS
        val cutEligible = allDifferent || globalCardinality
        val lpBounding = lpFits &&
            (lpEmittable || cutEligible || pseudoBoolean || circuit || constArrayElement || table)
        val lpCuts = lpFits && (cutEligible || pseudoBoolean)
        return base.copy(
            lpBounding = base.lpBounding || lpBounding,
            lpCuts = base.lpCuts || lpCuts,
            lpCutPool = base.lpCutPool || lpCuts,
            lpLearn = base.lpLearn || lpBounding,
            lpObjectiveBound = base.lpObjectiveBound || lpBounding,
            lpFixpoint = base.lpFixpoint || lpBounding,
            lpProbe = base.lpProbe || lpBounding,
            lpCircuit = base.lpCircuit || (lpFits && circuit),
            lpElement = base.lpElement || (lpFits && constArrayElement),
            lpTable = base.lpTable || (lpFits && table),
            lagrangian = base.lagrangian || allDifferent,
            energeticReasoning = base.energeticReasoning || cumulative,
        )
    }
}
