package com.eignex.klause.localsearch.strategy

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ArgminJump
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.schedule.ProgressWindow
import com.eignex.klause.localsearch.schedule.ScheduleBundle
import com.eignex.klause.localsearch.schedule.WeightSchedule
import com.eignex.klause.localsearch.scoring.MoveScoring

/**
 * Feasibility-Jump / ViolationLS strategy as a [SourceDrivenStrategy] recipe pinned to the
 * jump-and-reweight regime (B. Luteberget, G. Sartor, "Feasibility Jump: an LP-free Lagrangian MIP
 * heuristic", Mathematical Programming Computation 15, 2023, https://doi.org/10.1007/s12532-023-00234-8;
 * T. O. Davies, F. Didier, L. Perron, "ViolationLS: Constraint-Based Local Search in CP-SAT", CPAIOR
 * 2024, https://doi.org/10.1007/978-3-031-60597-0_16):
 *
 *  - **sources** = `{`[ArgminJump]`}` — every step jumps a hot-spot variable directly to its
 *    weighted-violation argmin, rather than stepping to an adjacent value.
 *  - **scoring** = [MoveScoring.Weighted] — the greedy descent is on the weighted-violation delta.
 *  - **acceptance** = [AcceptanceRule.Greedy] — take the most-negative weighted delta, no noise draw;
 *    FJ escapes minima by reweighting, not by a stochastic walk.
 *  - **weight schedule** = [WeightSchedule.feasibilityJump] — when cost stalls, fade old escalations
 *    by [weightDecay] and bump every violated factor by [weightIncrement], reshaping the argmin
 *    landscape toward the constraints that have been blocking progress.
 *  - **perturbation** = [StallPerturbation] — after [perturbAfter] applied moves with no strict cost
 *    drop, inject one random hot-spot jump.
 *
 * Learns and prunes nothing, so it carries no soundness obligation.
 */
@Suppress("FunctionNaming")
fun FeasibilityJump(
    /** Hot-spot variables jumped per pick (passed to [ArgminJump]). */
    candidateVars: Int = 8,
    /** Cap on domain values evaluated per wide-domain int variable. */
    maxValueTries: Int = ArgminJump.DEFAULT_MAX_VALUE_TRIES,
    /** Applied moves without a strict cost drop before weights are bumped. */
    weightBumpAfter: Int = 1,
    /** Per-bump increment added to each currently-violated factor's weight. */
    weightIncrement: Double = 1.0,
    /** Multiplicative fade applied to each weight's excess over its seeded baseline on every bump
     *  (`w ← base + (w − base)·weightDecay`); `1.0` disables fading (monotone escalation). */
    weightDecay: Double = 0.999,
    /** Applied moves without a strict cost drop before a perturbation kick fires; should exceed
     *  [weightBumpAfter] so weight escalation gets its chance first. `0` disables perturbation. */
    perturbAfter: Int = 200,
): SourceDrivenStrategy {
    require(candidateVars >= 1) { "candidateVars >= 1, got $candidateVars" }
    require(perturbAfter >= 0) { "perturbAfter >= 0, got $perturbAfter" }
    return SourceDrivenStrategy(
        sources = listOf(ConfiguredSource(ArgminJump(candidateVars, maxValueTries))),
        scoring = MoveScoring.Weighted,
        acceptance = AcceptanceRule.Greedy,
        schedule = ScheduleBundle(
            weights = WeightSchedule.feasibilityJump(weightBumpAfter, weightIncrement, weightDecay),
        ),
        perturbation = if (perturbAfter > 0) StallPerturbation(perturbAfter) else null,
        feasibleDescent = FeasibleDescent.RatchetAsConstraint,
    )
}

/**
 * The Feasibility-Jump diversification kick as a [perturbation hook][SourceDrivenStrategy.perturbation]: after
 * [perturbAfter] applied moves with no strict cost drop, returns one random hot-spot jump. Stateful: one instance
 * per search.
 */
class StallPerturbation(private val perturbAfter: Int) : (LocalSearchState) -> Move? {
    init {
        require(perturbAfter >= 1) { "perturbAfter >= 1, got $perturbAfter" }
    }

    private val progress = ProgressWindow()

    override fun invoke(state: LocalSearchState): Move? {
        progress.observe(state.step, state.cost)
        if (progress.elapsed(state.step) < perturbAfter) return null
        val kick = randomHotSpotJump(state) ?: return null
        progress.restartAt(state.step)
        return kick
    }

    private fun randomHotSpotJump(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        val fid = state.violated.random(state.rng)
        val scope = state.problem.factors[fid]
        val nInt = scope.intVars.size
        val nBool = scope.boolVars.size
        if (nInt + nBool == 0) return null
        val pick = state.rng.nextInt(nInt + nBool)
        if (pick < nInt) {
            val v = scope.intVars[pick]
            if (state.assumptions.isFrozenInt(v)) return null
            val d = state.rootDomains[v]
            if (d.isFixed) return null
            val nv = state.randomIntValue(v)
            if (nv == state.assignment.intValue(v)) return null
            return state.synthesizeChannelingMove(v, nv)
        }
        val v = scope.boolVars[pick - nInt]
        if (!state.moveSink.allowsBool(v)) return null
        return Move.BoolFlip(v)
    }
}
