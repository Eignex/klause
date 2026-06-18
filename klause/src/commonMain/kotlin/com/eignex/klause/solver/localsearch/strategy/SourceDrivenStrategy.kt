package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.localsearch.movesource.ConfiguredSource
import com.eignex.klause.solver.localsearch.movesource.Pool
import com.eignex.klause.solver.localsearch.schedule.WeightSchedule

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
    /** Basis for scoring candidates — weighted/raw net-delta or the shaped break score. */
    val scoring: MoveScoring = MoveScoring.Weighted,
    /** How a scored candidate is selected — greedy, WalkSAT noise, probSAT roulette, skewed-VNS, or SA. */
    val acceptance: AcceptanceRule = AcceptanceRule.Greedy,
    /** Tabu filter applied to the combined candidate pool before selection. */
    val tabu: TabuFilter = TabuFilter.Disabled,
    /** Optional violation-weight schedule (the CBLS/FJ stall-bump+decay family). Maintained off
     *  `(state.step, state.cost)` each pick; `null` (default) leaves the weights untouched. */
    val weightSchedule: WeightSchedule? = null,
    /** Configuration checking (CCASat): restrict candidates to variables whose configuration changed
     *  since their last flip, falling back to the full pool when all are CC-blocked. */
    val configurationChecking: Boolean = false,
    /** Optional perturbation: consulted once per pick before generation; a non-null result is taken
     *  immediately as a diversification kick. The closure owns its own trigger/stall state. */
    val perturbation: ((LocalSearchState) -> Move?)? = null,
) : Strategy {

    private val noiseSink = MoveSink()
    private val scoreSink = MoveSink()

    override fun pickMove(state: LocalSearchState): Move? {
        // Stall-driven weight maintenance first, so the bumped gradient scores this pick's candidates.
        weightSchedule?.maintain(
            state.step,
            state.cost,
            state.factorWeights,
            state.baseFactorWeights,
            state.violated.toIntArray(),
            state.rng,
        )
        // Perturbation escalation: a triggered kick pre-empts the normal pick.
        perturbation?.invoke(state)?.let { return it }

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
        // The noise/score pool split is preserved across the tabu + CC filters; the acceptance rule
        // applies it (stochastic rules draw from the noise pool only, deterministic ones range over
        // both) and returns null when both pools are empty.
        val noiseMoves = ccFilter(state, tabu.filter(state, noiseSink.list))
        val scoreMoves = ccFilter(state, tabu.filter(state, scoreSink.list))
        return acceptance.choose(state.rng, noiseMoves, scoreMoves) { score(state, it) }
    }

    /** Restrict [moves] to configuration-changed candidates when [configurationChecking] is on,
     *  falling back to the unfiltered pool when every candidate is CC-blocked. */
    private fun ccFilter(state: LocalSearchState, moves: List<Move>): List<Move> {
        if (!configurationChecking || moves.isEmpty()) return moves
        val cc = moves.filter { confChanged(state, it) }
        return cc.ifEmpty { moves }
    }

    /** A move is configuration-changed iff every variable it touches has moved since its last flip. */
    private fun confChanged(state: LocalSearchState, move: Move): Boolean = when (move) {
        is Move.BoolFlip -> state.boolConfChange[move.varId]
        is Move.IntSet -> state.intConfChange[move.varId]
        is Move.Compound -> move.parts.all { confChanged(state, it) }
    }

    /** Scored value on the [scoring] basis. Weighted/raw net-delta add the objective change once
     *  feasible (gated behind `cost == 0` so the infeasibility fight keeps the constraint gradient);
     *  the break basis already folds the shaped objective. Mirrors [Cbls]'s feasibility-first scoring. */
    private fun score(state: LocalSearchState, move: Move): Double = when (scoring) {
        MoveScoring.Break -> state.shapedBreakScore(move)
        MoveScoring.Weighted -> state.weightedNetDelta(move) + feasibleObjectiveDelta(state, move)
        MoveScoring.Raw -> state.netDelta(move).toDouble() + feasibleObjectiveDelta(state, move)
    }

    private fun feasibleObjectiveDelta(state: LocalSearchState, move: Move): Double =
        if (state.cost == 0L) state.shapedObjectiveDelta(move) else 0.0
}
