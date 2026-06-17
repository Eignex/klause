package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.movesource.ArgminJump
import com.eignex.klause.solver.localsearch.schedule.WeightSchedule

/**
 * Feasibility-Jump / ViolationLS strategy (Davies et al., CPAIOR 2024; epic #698). An LS arm whose
 * move model is fundamentally different from the WalkSAT/CBLS step families: every step **jumps a
 * variable directly to its weighted-violation argmin** ([ArgminJump]) rather than stepping by a
 * flip or ±1, and it escapes local minima by **adaptive per-constraint weights** rather than a
 * stochastic walk. Aggressive toward feasibility and orthogonal to the existing arms, so it is most
 * valuable raced alongside them in the parallel portfolio.
 *
 * Per [pickMove]:
 *  1. **Adaptive weights.** When `state.cost` has not strictly dropped for [weightBumpAfter] applied
 *     moves, fade the excess of every weight over its seeded baseline by [weightDecay] (old
 *     escalations forget) and bump every currently-violated factor's weight by [weightIncrement]
 *     (resistant constraints get heavier). This reshapes the argmin landscape so the next jumps
 *     target the constraints that have been blocking progress — the FJ analogue of a restart.
 *  2. **Jump.** Collect the argmin jumps of [candidateVars] hot-spot variables and greedily take the
 *     one with the most-negative weighted-violation delta ([LocalSearchState.weightedNetDelta]).
 *  3. **Perturbation.** After [perturbAfter] applied moves with no strict cost drop — long enough
 *     that the weight bumps alone have not escaped — return a single random hot-spot jump (a
 *     diversification kick) and restart the no-progress clock.
 *
 * `null` is returned only when there is nothing to repair (`cost == 0`) or no eligible variable,
 * letting the engine restart. The arm learns and prunes nothing, so it carries no soundness
 * obligation; the `deltaIf*` probes it relies on are guarded by the existing delta-consistency oracle.
 */
class FeasibilityJump(
    /** Hot-spot variables jumped per pick (passed to [ArgminJump]). */
    val candidateVars: Int = 8,
    /** Cap on domain values evaluated per wide-domain int variable. */
    val maxValueTries: Int = ArgminJump.DEFAULT_MAX_VALUE_TRIES,
    /** Applied moves without a strict cost drop before weights are bumped. */
    val weightBumpAfter: Int = 1,
    /** Per-bump increment added to each currently-violated factor's weight. */
    val weightIncrement: Double = 1.0,
    /** Multiplicative fade applied to each weight's excess over its seeded baseline on every bump
     *  (`w ← base + (w − base)·weightDecay`); `1.0` disables fading (monotone escalation). */
    val weightDecay: Double = 0.999,
    /** Applied moves without a strict cost drop before a perturbation kick fires; should exceed
     *  [weightBumpAfter] so weight escalation gets its chance first. `0` disables perturbation. */
    val perturbAfter: Int = 200,
) : Strategy {

    init {
        require(candidateVars >= 1) { "candidateVars >= 1, got $candidateVars" }
        require(weightBumpAfter >= 1) { "weightBumpAfter >= 1, got $weightBumpAfter" }
        require(weightIncrement > 0.0) { "weightIncrement > 0, got $weightIncrement" }
        require(weightDecay in 0.0..1.0) { "weightDecay ∈ [0, 1], got $weightDecay" }
        require(perturbAfter >= 0) { "perturbAfter >= 0, got $perturbAfter" }
    }

    private val argmin = ArgminJump(candidateVars, maxValueTries)

    /** The bump + geometric-decay weight maintenance, shared with Cbls via the schedule axis. */
    private val weightSchedule = WeightSchedule.feasibilityJump(weightBumpAfter, weightIncrement, weightDecay)

    private var lastImprovingStep: Long = -1L
    private var lastSeenStep: Long = -1L
    private var lastCost: Long = Long.MAX_VALUE

    /** Step of the last strict cost decrease — drives the perturbation window (not reset by a bump). */
    private var lastDropStep: Long = 0L

    override fun pickMove(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null

        // Stall tracking off the engine-maintained step counter (mirrors Cbls): bump weights when
        // cost has not strictly dropped for [weightBumpAfter] applied moves.
        if (state.step < lastSeenStep) {
            lastImprovingStep = state.step
            lastDropStep = state.step
            lastCost = state.cost
        }
        if (state.step != lastSeenStep) {
            if (state.cost < lastCost) {
                lastImprovingStep = state.step
                lastCost = state.cost
                lastDropStep = state.step
            } else if (state.step - lastImprovingStep >= weightBumpAfter) {
                weightSchedule.bumpAndRelax(
                    state.factorWeights,
                    state.baseFactorWeights,
                    state.violated.toIntArray(),
                    state.rng,
                )
                lastImprovingStep = state.step
            }
            lastSeenStep = state.step
        }

        // Perturbation: weight escalation has had a long window and still no progress.
        if (perturbAfter > 0 && state.step - lastDropStep >= perturbAfter) {
            val kick = perturbation(state)
            if (kick != null) {
                lastDropStep = state.step // fresh window after the kick
                return kick
            }
        }

        val sink = state.moveSink
        sink.clear()
        argmin.generate(state, sink)
        val moves = sink.list
        if (moves.isEmpty()) return null

        // Greedy: the most-negative weighted-violation delta (reservoir tie-break for uniformity).
        var best = moves[0]
        var bestScore = state.weightedNetDelta(best)
        var tieCount = 1
        for (i in 1 until moves.size) {
            val s = state.weightedNetDelta(moves[i])
            if (s < bestScore) {
                best = moves[i]
                bestScore = s
                tieCount = 1
            } else if (s == bestScore) {
                tieCount++
                if (state.rng.nextInt(tieCount) == 0) best = moves[i]
            }
        }
        return best
    }

    /** One diversification kick: jump a random variable of a random violated factor to a random
     *  value (channeling-aware for int vars), or flip it. Skips frozen variables; returns null when
     *  none is eligible. */
    private fun perturbation(state: LocalSearchState): Move? {
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
