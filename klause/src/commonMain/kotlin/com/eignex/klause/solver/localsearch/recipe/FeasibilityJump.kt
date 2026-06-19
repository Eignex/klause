package com.eignex.klause.solver.localsearch.recipe

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.solver.localsearch.driver.SourceDrivenStrategy
import com.eignex.klause.solver.localsearch.movesource.ArgminJump
import com.eignex.klause.solver.localsearch.movesource.ConfiguredSource
import com.eignex.klause.solver.localsearch.schedule.ScheduleBundle
import com.eignex.klause.solver.localsearch.schedule.WeightSchedule
import com.eignex.klause.solver.localsearch.scoring.MoveScoring

/**
 * Feasibility-Jump / ViolationLS strategy (Davies et al., CPAIOR 2024), re-expressed as a
 * [SourceDrivenStrategy] recipe. FJ is exactly the driver's four axes pinned to the
 * jump-and-reweight regime:
 *
 *  - **sources** = `{`[ArgminJump]`}` — every step jumps a hot-spot variable directly to its
 *    weighted-violation argmin, rather than stepping by a flip or ±1.
 *  - **scoring** = [MoveScoring.Weighted] — the greedy descent is on the weighted-violation delta.
 *  - **acceptance** = [AcceptanceRule.Greedy] — take the most-negative weighted delta, no noise draw;
 *    FJ escapes minima by reweighting, not by a stochastic walk.
 *  - **weight schedule** = [WeightSchedule.feasibilityJump] — when cost stalls, fade old escalations
 *    by [weightDecay] and bump every violated factor by [weightIncrement], reshaping the argmin
 *    landscape toward the constraints that have been blocking progress (the FJ analogue of a restart).
 *  - **perturbation** = [StallPerturbation] — after [perturbAfter] applied moves with no strict cost
 *    drop (long enough that the weight bumps alone have not escaped) inject one random hot-spot jump.
 *
 * Aggressive toward feasibility and orthogonal to the WalkSAT/CBLS/SA step families, so it is most
 * valuable raced alongside them in the parallel portfolio. The arm learns and prunes nothing, so it
 * carries no soundness obligation.
 */
@Suppress("FunctionNaming") // factory mirroring the historical strategy constructor it replaced
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
    )
}

/**
 * The Feasibility-Jump diversification kick as a driver [perturbation hook][SourceDrivenStrategy.perturbation]
 *: after [perturbAfter] applied moves with no strict cost drop — long enough that weight
 * escalation has had its chance — return one random hot-spot jump and restart the no-progress window.
 *
 * Stateful (one per search): it tracks the stall window off the engine-maintained `state.step`,
 * re-anchoring on a restart (rewound step) and on every strict cost drop. Returns `null` when the
 * window has not elapsed or no variable is eligible, leaving the driver to its normal pick.
 */
class StallPerturbation(private val perturbAfter: Int) : (LocalSearchState) -> Move? {
    init {
        require(perturbAfter >= 1) { "perturbAfter >= 1, got $perturbAfter" }
    }

    private var lastSeenStep: Long = -1L
    private var lastDropStep: Long = 0L
    private var lastCost: Long = Long.MAX_VALUE

    override fun invoke(state: LocalSearchState): Move? {
        // Stall tracking off the engine step counter: the window resets on a restart and on every
        // strict cost decrease, mirroring the schedule axis's own stall detection.
        if (state.step < lastSeenStep) {
            lastDropStep = state.step
            lastCost = state.cost
            lastSeenStep = state.step
        } else if (state.step != lastSeenStep) {
            if (state.cost < lastCost) {
                lastCost = state.cost
                lastDropStep = state.step
            }
            lastSeenStep = state.step
        }
        if (state.step - lastDropStep < perturbAfter) return null
        val kick = randomHotSpotJump(state) ?: return null
        lastDropStep = state.step // fresh window after the kick
        return kick
    }

    /** One diversification kick: jump a random variable of a random violated factor to a random
     *  value (channeling-aware for int vars), or flip it. Skips frozen variables; returns null when
     *  none is eligible. */
    private fun randomHotSpotJump(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        val f = state.factors[state.violated.random(state.rng)]
        val nInt = f.intVars.size
        val nBool = f.boolVars.size
        if (nInt + nBool == 0) return null
        val pick = state.rng.nextInt(nInt + nBool)
        if (pick < nInt) {
            val v = f.intVars[pick]
            if (state.assumptions.isFrozenInt(v)) return null
            val d = state.problem.intDomains[v]
            val span = (d.max.toLong() - d.min.toLong()).toInt()
            if (span <= 0) return null
            val nv = d.min + state.rng.nextInt(span + 1)
            if (nv == state.assignment.intValue(v)) return null
            return state.synthesizeChannelingMove(v, nv)
        }
        val v = f.boolVars[pick - nInt]
        if (state.assumptions.isFrozenBool(v)) return null
        return Move.BoolFlip(v)
    }
}
