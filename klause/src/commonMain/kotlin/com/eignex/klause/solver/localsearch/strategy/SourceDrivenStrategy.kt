package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.localsearch.movesource.ConfiguredSource
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
 * arm adds `{SatisfiedStructured, ObjectiveSeed}`, and so on. [scoring] (the scoring axis) and
 * [acceptance] (the acceptance axis) stay first-class, so the driver does not blur the distinct
 * strategy behaviours — it removes only the duplicated *generation*.
 *
 * This driver is the behaviour-neutral substrate the bespoke strategies migrate onto incrementally;
 * it is not itself a drop-in replacement for [Cbls]'s tuned stall/weight/ladder schedule.
 */
class SourceDrivenStrategy(
    /** The sources this strategy draws from, with their per-source caps and enable gates. */
    val sources: List<ConfiguredSource>,
    /** Basis for scoring candidates — the CBLS weighted gradient or the raw violation delta. */
    val scoring: MoveScoring = MoveScoring.Weighted,
    /** How a scored candidate is selected — greedy, WalkSAT noise, probSAT roulette, or skewed-VNS. */
    val acceptance: AcceptanceRule = AcceptanceRule.Greedy,
    /** Tabu filter applied to the combined candidate pool before selection. */
    val tabu: TabuFilter = TabuFilter.Disabled,
) : Strategy {

    private val noiseSink = MoveSink()
    private val scoreSink = MoveSink()

    override fun pickMove(state: LocalSearchState): Move? {
        noiseSink.clear()
        scoreSink.clear()
        noiseSink.setAssumptions(state.assumptions)
        scoreSink.setAssumptions(state.assumptions)
        noiseSink.setInvariants(state.invariants)
        scoreSink.setInvariants(state.invariants)
        for (cs in sources) {
            if (!cs.enabled) continue
            if (!cs.source.phase.appliesAt(state.cost)) continue
            val sink = if (cs.source.pool == Pool.NoiseEligible) noiseSink else scoreSink
            cs.source.generate(state, sink)
        }
        // The noise/score pool split is preserved across the tabu filter; the acceptance rule
        // applies it (stochastic rules draw from the noise pool only, deterministic ones range over
        // both) and returns null when both pools are empty.
        val noiseMoves = tabu.filter(state, noiseSink.list)
        val scoreMoves = tabu.filter(state, scoreSink.list)
        return acceptance.choose(state.rng, noiseMoves, scoreMoves) { score(state, it) }
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
