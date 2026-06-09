package com.eignex.klause.solver.backtrack

import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.AllDifferentExcept
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.ReifiedLinear

/**
 * Structural auto-configuration of the LP-relaxation family (#245). Each technique is enabled when —
 * and only when — the problem actually contains the structure it targets. Applicability is a
 * structural fact, so this never *speculates* about performance; it just stops the LP machinery from
 * being a manual per-instance decision:
 *
 *  - **[BacktrackParams.lpBounding]** — there is genuine integer-linear structure ([Linear] /
 *    [ReifiedLinear]) the relaxation can exploit, or a cut-eligible global whose cuts need the LP.
 *  - **[BacktrackParams.lpCuts]** — an [AllDifferent] (Hall / assignment cuts) or [GlobalCardinality]
 *    (occurrence sum cuts) is present.
 *  - **[BacktrackParams.lagrangian]** — an [AllDifferent] is present (the weighted-assignment bound).
 *  - **[BacktrackParams.energeticReasoning]** — a [Cumulative] is present.
 *
 * Every flag is OR-ed onto `base`, so an explicit caller setting is never turned *off*, and the
 * default ([recommend] is opt-in — nothing calls it implicitly) leaves the existing behaviour intact.
 * Picking *which* techniques pay off on a given corpus, and whether any should flip on by default,
 * is the empirical half of #245 and stays with the benchmark.
 */
object LpAutoConfig {

    /** `base` with each LP-family flag enabled where [problem]'s structure makes it applicable. */
    fun recommend(problem: Problem, base: BacktrackParams = BacktrackParams()): BacktrackParams {
        var lpEmittable = false
        var allDifferent = false
        var globalCardinality = false
        var cumulative = false
        for (f in problem.factors) {
            when (f) {
                is Linear, is ReifiedLinear -> lpEmittable = true
                is AllDifferent, is AllDifferentExcept -> allDifferent = true
                is GlobalCardinality -> globalCardinality = true
                is Cumulative -> cumulative = true
                else -> Unit
            }
        }
        val cutEligible = allDifferent || globalCardinality
        return base.copy(
            lpBounding = base.lpBounding || lpEmittable || cutEligible,
            lpCuts = base.lpCuts || cutEligible,
            lagrangian = base.lagrangian || allDifferent,
            energeticReasoning = base.energeticReasoning || cumulative,
        )
    }
}
