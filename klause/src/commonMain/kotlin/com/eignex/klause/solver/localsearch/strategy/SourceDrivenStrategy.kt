package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.localsearch.movesource.ConfiguredSource
import com.eignex.klause.solver.localsearch.movesource.MoveGenContext
import com.eignex.klause.solver.localsearch.movesource.Pool

/**
 * The shared local-search driver (epic #710): a [Strategy] expressed purely as *policy over move
 * sources*. It owns no generation loop — it collects candidates from a configured set of
 * [com.eignex.klause.solver.localsearch.movesource.MoveSource]s, then scores and selects. Every
 * gate the bespoke strategies used to re-implement per call lives here once:
 *
 *  - **Phase gating** — a source is consulted only when its
 *    [com.eignex.klause.solver.localsearch.movesource.Phase] applies to the current `state.cost`,
 *    replacing the per-generator `cost` re-checks.
 *  - **Noise/score pool split** — [Pool.NoiseEligible] moves are routed to a pool the random/noise
 *    draw can take; [Pool.ScoreOnly] moves (coordinated swaps/chains) compete by score only, never
 *    by dice. The rule that "coordinated escapes never enter the noise draw" is enforced once,
 *    by source property, rather than re-encoded in each strategy.
 *
 * Because a source is consumed entirely by configuration, *any* source becomes available to *any*
 * strategy with no new generation code: a focused arm is `{ViolatedRepairs}`, a structured-descent
 * arm adds `{SatisfiedStructured, ObjectiveSeed}`, and so on. Scoring stays first-class via
 * [scoring] (the CBLS weighted gradient vs the raw violation delta), so the driver does not blur
 * the distinct strategy behaviours — it removes only the duplicated *generation*.
 *
 * This driver is the behaviour-neutral substrate the bespoke strategies migrate onto incrementally;
 * it is not itself a drop-in replacement for [Cbls]'s tuned stall/weight/ladder schedule.
 */
class SourceDrivenStrategy(
    /** The sources this strategy draws from, with their per-source caps and enable gates. */
    val sources: List<ConfiguredSource>,
    /** Basis for scoring candidates — the CBLS weighted gradient or the raw violation delta. */
    val scoring: MoveScoring = MoveScoring.Weighted,
    /** Probability of taking a uniformly-random move from the noise-eligible pool instead of the
     *  best-scored move. `0.0` (default) = pure greedy descent. */
    val noiseProbability: Double = 0.0,
    /** Tabu filter applied to the combined candidate pool before selection. */
    val tabu: TabuFilter = TabuFilter.Disabled,
) : Strategy {

    init {
        require(noiseProbability in 0.0..1.0) { "noiseProbability ∈ [0, 1], got $noiseProbability" }
    }

    private val noiseSink = MoveSink()
    private val scoreSink = MoveSink()

    override fun pickMove(state: LocalSearchState): Move? {
        noiseSink.clear()
        scoreSink.clear()
        noiseSink.setAssumptions(state.assumptions)
        scoreSink.setAssumptions(state.assumptions)
        noiseSink.setInvariants(state.invariants)
        scoreSink.setInvariants(state.invariants)
        val ctx = MoveGenContext(state)
        for (cs in sources) {
            if (!cs.enabled) continue
            if (!cs.source.phase.appliesAt(state.cost)) continue
            val sink = if (cs.source.pool == Pool.NoiseEligible) noiseSink else scoreSink
            cs.source.generate(ctx, sink)
        }
        val noiseMoves = tabu.filter(state, noiseSink.list)
        val scoreMoves = tabu.filter(state, scoreSink.list)
        if (noiseMoves.isEmpty() && scoreMoves.isEmpty()) return null

        // Noise draw: only the noise-eligible pool is eligible — coordinated score-only moves are
        // never taken by dice.
        if (noiseMoves.isNotEmpty() && state.rng.nextDouble() < noiseProbability) {
            return noiseMoves[state.rng.nextInt(noiseMoves.size)]
        }

        // Greedy: minimum scored move across both pools (reservoir tie-break for uniformity).
        var best: Move? = null
        var bestScore = Double.POSITIVE_INFINITY
        var tieCount = 0
        for (pool in arrayOf(noiseMoves, scoreMoves)) {
            for (m in pool) {
                val s = score(state, m)
                if (s < bestScore) {
                    best = m
                    bestScore = s
                    tieCount = 1
                } else if (s == bestScore) {
                    tieCount++
                    if (state.rng.nextInt(tieCount) == 0) best = m
                }
            }
        }
        return best
    }

    /** Scored delta: the [scoring]-basis violation change, plus the objective change once feasible
     *  (the objective is gated behind `cost == 0` so the infeasibility fight isn't pulled off the
     *  constraint gradient). Mirrors [Cbls]'s feasibility-first scoring. */
    private fun score(state: LocalSearchState, move: Move): Double {
        val violationDelta = when (scoring) {
            MoveScoring.Weighted -> state.weightedNetDelta(move)
            MoveScoring.Raw -> state.netDelta(move).toDouble()
        }
        val objDelta = if (state.cost == 0L) state.shapedObjectiveDelta(move) else 0.0
        return violationDelta + objDelta
    }
}
