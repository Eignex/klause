package com.eignex.klause.solver.localsearch.driver

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.solver.localsearch.movesource.ConfiguredSource
import com.eignex.klause.solver.localsearch.movesource.Pool
import com.eignex.klause.solver.localsearch.schedule.NoiseSchedule
import com.eignex.klause.solver.localsearch.schedule.RoundAccumulator
import com.eignex.klause.solver.localsearch.schedule.ScheduleBundle
import com.eignex.klause.solver.localsearch.schedule.StallSchedule
import com.eignex.klause.solver.localsearch.scoring.MoveScoring

/**
 * The shared local-search driver: the sole strategy, expressed purely as *policy over move sources*.
 * It owns no generation loop — it collects candidates from a configured set of
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
 * recipe with no new generation code: a focused arm is `{ViolatedRepairs}`, a structured-descent arm
 * adds `{SatisfiedStructured, ObjectiveSeed}`, and so on. [scoring] (the scoring axis), [acceptance]
 * (the acceptance axis), and [schedule] (the schedule axis) stay first-class, so the recipe's
 * behaviour is its four axes — the driver removes only the duplicated *generation*.
 *
 * Every local-search arm — CBLS, Feasibility-Jump, the WalkSAT/probSAT/SA family — is a named recipe
 * over this driver (see the `recipe` package).
 */
class SourceDrivenStrategy(
    /** The sources this strategy draws from, with their per-source caps and enable gates. */
    val sources: List<ConfiguredSource>,
    /** Basis for scoring candidates — weighted/raw net-delta or the shaped break score. */
    val scoring: MoveScoring = MoveScoring.Weighted,
    /** How a scored candidate is selected — greedy, WalkSAT noise, probSAT roulette, skewed-VNS, or SA. */
    val acceptance: AcceptanceRule = AcceptanceRule.Greedy,
    /** The schedule axis: the [ScheduleBundle] of tempo policies — temperature, violation
     *  weights, diversification noise, restart cadence. Each member is driven at its native cadence
     *  (weights per step; temperature per pick + per round; restart by the engine). The default empty
     *  bundle leaves every dimension off. */
    val schedule: ScheduleBundle = ScheduleBundle(),
    /** Tabu filter applied to the combined candidate pool before selection. */
    val tabu: TabuFilter = TabuFilter.Disabled,
    /** Configuration checking (CCASat): restrict candidates to variables whose configuration changed
     *  since their last flip, falling back to the full pool when all are CC-blocked. */
    val configurationChecking: Boolean = false,
    /** Optional perturbation: consulted once per pick before generation; a non-null result is taken
     *  immediately as a diversification kick. The closure owns its own trigger/stall state. */
    val perturbation: ((LocalSearchState) -> Move?)? = null,
    /** Whether this strategy drives objective descent at feasibility (it has feasibility-phase
     *  sources that score satisfied/objective candidates at `cost == 0`), rather than bailing for the
     *  engine's built-in descent. The unified-minimize path keys off this; the `Cbls` recipe sets it. */
    val drivesObjectiveDescent: Boolean = false,
) {

    /** Round feedback retunes the schedule axis's temperature schedule (e.g. AdaptiveCooling);
     *  accumulation is gated off when no temperature schedule is present. */
    val wantsRoundFeedback: Boolean get() = schedule.temperature != null

    /** Feed a completed round of move statistics to the schedule axis's temperature schedule, the
     *  round ending at engine [step]. Only meaningful when [wantsRoundFeedback]; a no-op otherwise. */
    fun observeRound(acc: RoundAccumulator, step: Long) {
        val temperature = schedule.temperature ?: return
        temperature.observe(acc.snapshot(temperature.temperature, step))
    }

    private val noiseSink = MoveSink()
    private val scoreSink = MoveSink()

    /** Pick the next move to apply, or `null` when no candidate is available (the engine restarts). */
    fun pickMove(state: LocalSearchState): Move? {
        // Stall-driven weight maintenance first, so the bumped gradient scores this pick's candidates.
        schedule.weights?.maintain(
            state.step,
            state.cost,
            state.factorWeights,
            state.baseFactorWeights,
            state.violated.toIntArray(),
            state.rng,
        )
        // The stall signal (schedule axis): advance the no-progress window, then gate the
        // plateau-escape sources and the effective noise on it. A StallSchedule is the noise member
        // and the gate at once; absent it, nothing is stall-gated.
        val stall = schedule.noise as? StallSchedule
        stall?.update(state.step, state.cost)
        val stalled = stall?.stalled ?: false
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
            if (cs.stallGated && !stalled) continue
            if (!cs.effectivePhase.appliesAt(state.cost)) continue
            val sink = if (cs.source.pool == Pool.NoiseEligible) noiseSink else scoreSink
            cs.source.generate(state, sink)
        }
        // The noise/score pool split is preserved across the CC + tabu filters; the acceptance rule
        // applies it (stochastic rules draw from the noise pool only, deterministic ones range over
        // both) and returns null when both pools are empty. CC precedes tabu (matching the focused
        // WalkSAT/probSAT order): the configuration filter is the primary focus, tabu the cycle-breaker
        // layered on what survives it.
        val ccNoise = ccFilter(state, noiseSink.list)
        val noiseFiltered = tabu.filter(state, ccNoise)
        // While stalled, never let tabu starve the noise pool into a null pick (a full restart that
        // discards plateau progress): fall back to the unfiltered candidates so the search keeps
        // walking the plateau. Off-stall, an empty tabu pool still yields the normal restart path.
        val noiseMoves = if (noiseFiltered.isEmpty() && stalled) ccNoise else noiseFiltered
        val scoreMoves = tabu.filter(state, ccFilter(state, scoreSink.list))
        // Diversification noise is the schedule axis's: a NoiseSchedule retunes its level (the
        // adaptive WalkSAT/probSAT controllers off the running cost, the StallSchedule off the stall
        // window) and steers the acceptance rule (WalkSAT noise / probSAT cb). The noise-free rules
        // ignore the level.
        val noiseSchedule = schedule.noise as? NoiseSchedule
        val effectiveAcceptance = if (noiseSchedule != null && (noiseMoves.isNotEmpty() || scoreMoves.isNotEmpty())) {
            if (stall == null) noiseSchedule.observe(state.cost)
            acceptance.steered(noiseSchedule.level)
        } else {
            acceptance
        }
        // Temperature is the schedule axis's; the acceptance rule only reads it (Metropolis). The
        // driver advances the schedule once per pick that sampled the noise pool, matching the former
        // step-per-Metropolis-choose cadence — so acceptance stays pure and schedule stays the axis.
        val temperature = schedule.temperature?.temperature ?: Double.POSITIVE_INFINITY
        val move = effectiveAcceptance.choose(state.rng, noiseMoves, scoreMoves, temperature) { score(state, it) }
        if (effectiveAcceptance is AcceptanceRule.Metropolis && noiseMoves.isNotEmpty()) schedule.temperature?.step()
        return move
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
     *  the break basis already folds the shaped objective. Mirrors the CBLS feasibility-first scoring. */
    private fun score(state: LocalSearchState, move: Move): Double = when (scoring) {
        MoveScoring.Break -> state.shapedBreakScore(move)
        MoveScoring.Weighted -> state.weightedNetDelta(move) + feasibleObjectiveDelta(state, move)
        MoveScoring.Raw -> state.netDelta(move).toDouble() + feasibleObjectiveDelta(state, move)
    }

    private fun feasibleObjectiveDelta(state: LocalSearchState, move: Move): Double =
        if (state.cost == 0L) state.shapedObjectiveDelta(move) else 0.0
}
