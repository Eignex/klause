package com.eignex.klause.localsearch.strategy

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.TabuFilter
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.Pool
import com.eignex.klause.localsearch.schedule.NoiseSchedule
import com.eignex.klause.localsearch.schedule.RoundAccumulator
import com.eignex.klause.localsearch.schedule.ScheduleBundle
import com.eignex.klause.localsearch.schedule.StallSchedule
import com.eignex.klause.localsearch.scoring.MoveScoring

/** Temperature a Metropolis acceptance falls back to when the schedule axis supplies none. */
private const val DEFAULT_METROPOLIS_TEMPERATURE = 1.0

/**
 * How a strategy conducts the feasible (`cost == 0`) phase of a minimize — an explicit per-strategy
 * choice with no default, so a portfolio arm can never fall into a descent it didn't ask for. The
 * [com.eignex.klause.localsearch.LocalSearchSolver] dispatches the feasible phase exhaustively on it.
 */
enum class FeasibleDescent {
    /** Violation-native (probSAT / WalkSAT / feasibility-jump): the strategy does not optimize at
     *  feasibility itself. On a COP the portfolio overlays an `objective ≤ incumbent` factor so the
     *  objective slack re-enters the violation set and is repaired like any constraint; on a CSP the
     *  strategy is a pure feasibility finder. */
    RatchetAsConstraint,

    /** The strategy's own `pickMove` (its sources + [AcceptanceRule]) owns the feasible walk: the engine
     *  commits whatever feasibility-preserving move it picks. CBLS descends greedily on its objective
     *  and structured/pair-swap sources; simulated annealing's Metropolis steps through worse-objective
     *  states. */
    SelfOwned,
}

/**
 * The shared local-search driver, expressed as *policy over move sources*. It collects candidates
 * from a configured set of [com.eignex.klause.localsearch.movesource.MoveSource]s, then scores
 * and selects, applying two gates once:
 *
 *  - **Phase gating** — a source is consulted only when its
 *    [com.eignex.klause.localsearch.movesource.Phase] applies to the current `state.cost`.
 *  - **Noise/score pool split** — [Pool.NoiseEligible] moves are routed to a pool the random/noise
 *    draw can take; [Pool.ScoreOnly] moves (coordinated swaps/chains) compete by score only, never
 *    by dice.
 *
 * A source is consumed entirely by configuration, so any source is available to any recipe with no
 * new generation code: a focused arm is `{ViolatedRepairs}`, a structured-descent arm adds
 * `{SatisfiedStructured, ObjectiveSeed}`. The recipe's behaviour is its four axes — [sources],
 * [scoring], [acceptance], [schedule]. Each local-search arm (CBLS, Feasibility-Jump, the
 * WalkSAT/probSAT/SA family) is a named recipe over this driver, built by the factories in this package.
 */
class SourceDrivenStrategy(
    /** The sources this strategy draws from, with their per-source caps and enable gates. */
    val sources: List<ConfiguredSource>,
    /** Basis for scoring candidates — weighted/raw net-delta or the shaped break score. */
    val scoring: MoveScoring = MoveScoring.Weighted,
    /** How a scored candidate is selected — greedy, WalkSAT noise, probSAT roulette, skewed-VNS, or SA. */
    val acceptance: AcceptanceRule = AcceptanceRule.Greedy,
    /** The schedule axis: the [ScheduleBundle] of tempo policies — temperature, violation weights,
     *  diversification noise, restart cadence. Each member is driven at its native cadence (weights
     *  per step; temperature per pick + per round; restart by the engine). The default leaves every
     *  dimension off. */
    val schedule: ScheduleBundle = ScheduleBundle(),
    /** Tabu filter applied to the combined candidate pool before selection. */
    val tabu: TabuFilter = TabuFilter.Disabled,
    /** Configuration checking (CCASat): restrict candidates to variables whose configuration changed
     *  since their last flip, falling back to the full pool when all are CC-blocked. */
    val configurationChecking: Boolean = false,
    /** Optional perturbation: consulted once per pick before generation; a non-null result is taken
     *  immediately as a diversification kick. The closure owns its own trigger/stall state. */
    val perturbation: ((LocalSearchState) -> Move?)? = null,
    /** How this strategy conducts the feasible (`cost == 0`) phase of a minimize — see [FeasibleDescent].
     *  Explicit with no default: every strategy declares it, so no portfolio arm can silently inherit a
     *  descent it didn't choose (the `LocalSearchSolver` dispatches on it exhaustively). */
    val feasibleDescent: FeasibleDescent,
    /** Acceptance used only in the feasible (`cost == 0`) phase, when the objective is scored, in place
     *  of [acceptance]. Lets a strategy fight infeasibility with noise but descend the objective
     *  strictly: CBLS sets [AcceptanceRule.GreedyDescent] so it takes only objective-improving moves and
     *  otherwise returns `null` (a local optimum → restart). `null` reuses [acceptance] in both phases. */
    val feasibleAcceptance: AcceptanceRule? = null,
    /** Re-sample budget for the [FeasibleDescent.SelfOwned] feasible walk: consecutive picks that find no
     *  move (a `null` pick) tolerated before the engine restarts. `0` restarts on the first miss — correct
     *  when the strategy generates exhaustively, so a `null` is a genuine local optimum. A positive cap
     *  suits a *sampled* strategy, where a `null` is usually just a draw that missed an existing improving
     *  move: re-drawing the same basin this many times (letting the stall machinery engage) keeps one
     *  unlucky draw from discarding a good partial solution. */
    val feasibleResampleCap: Int = 0,
) {

    /** A copy with selected axes replaced, used by recipe assembly and the axis-edit transform to
     *  swap one dimension while preserving the rest. */
    fun copy(
        sources: List<ConfiguredSource> = this.sources,
        scoring: MoveScoring = this.scoring,
        acceptance: AcceptanceRule = this.acceptance,
        schedule: ScheduleBundle = this.schedule,
        tabu: TabuFilter = this.tabu,
        configurationChecking: Boolean = this.configurationChecking,
        perturbation: ((LocalSearchState) -> Move?)? = this.perturbation,
        feasibleDescent: FeasibleDescent = this.feasibleDescent,
        feasibleAcceptance: AcceptanceRule? = this.feasibleAcceptance,
        feasibleResampleCap: Int = this.feasibleResampleCap,
    ): SourceDrivenStrategy = SourceDrivenStrategy(
        sources = sources,
        scoring = scoring,
        acceptance = acceptance,
        schedule = schedule,
        tabu = tabu,
        configurationChecking = configurationChecking,
        perturbation = perturbation,
        feasibleDescent = feasibleDescent,
        feasibleAcceptance = feasibleAcceptance,
        feasibleResampleCap = feasibleResampleCap,
    )

    /** Whether round feedback retunes the temperature schedule; off when no temperature schedule is present. */
    val wantsRoundFeedback: Boolean get() = schedule.temperature != null

    /** Feed a completed round of move statistics to the temperature schedule, the round ending at
     *  engine [step]. A no-op unless [wantsRoundFeedback]. */
    fun observeRound(acc: RoundAccumulator, step: Long) {
        val temperature = schedule.temperature ?: return
        temperature.observe(acc.snapshot(temperature.temperature, step))
    }

    private val noiseSink = MoveSink()
    private val scoreSink = MoveSink()

    /** Pick the next move to apply, or `null` when no candidate is available (the engine restarts). */
    fun pickMove(state: LocalSearchState): Move? {
        // Weight maintenance first, so the bumped gradient scores this pick's candidates.
        schedule.weights?.maintain(
            state.step,
            state.cost,
            state.factorWeights,
            state.baseFactorWeights,
            state.violated.toIntArray(),
            state.rng,
        )
        // The stall signal: advance the no-progress window, then gate the plateau-escape sources and
        // the effective noise on it. A StallSchedule is the noise member and the gate at once.
        val stall = schedule.noise as? StallSchedule
        stall?.update(state.step, state.cost)
        val stalled = stall?.stalled ?: false
        // A triggered kick pre-empts the normal pick.
        perturbation?.invoke(state)?.let { return it }

        noiseSink.clear()
        scoreSink.clear()
        noiseSink.setAssumptions(state.assumptions)
        scoreSink.setAssumptions(state.assumptions)
        noiseSink.setInvariants(state.invariants)
        scoreSink.setInvariants(state.invariants)
        noiseSink.setOwners(state.ownerInt)
        scoreSink.setOwners(state.ownerInt)
        for (cs in sources) {
            if (!cs.enabled) continue
            if (cs.stallGated && !stalled) continue
            if (!cs.effectivePhase.appliesAt(state.cost)) continue
            val sink = if (cs.source.pool == Pool.NoiseEligible) noiseSink else scoreSink
            cs.source.generate(state, sink)
        }
        // The noise/score pool split is preserved across the CC + tabu filters. CC precedes tabu: the
        // configuration filter is the primary focus, tabu the cycle-breaker layered on what survives it.
        val ccNoise = ccFilter(state, noiseSink.list)
        val noiseFiltered = tabu.filter(state, ccNoise)
        // While stalled, never let tabu starve the noise pool into a null pick (a restart that discards
        // plateau progress): fall back to the unfiltered candidates so the search keeps walking the plateau.
        val noiseMoves = if (noiseFiltered.isEmpty() && stalled) ccNoise else noiseFiltered
        val scoreMoves = tabu.filter(state, ccFilter(state, scoreSink.list))
        // A NoiseSchedule retunes its level and steers the acceptance rule (WalkSAT noise / probSAT cb);
        // noise-free rules ignore the level.
        val noiseSchedule = schedule.noise as? NoiseSchedule
        val effectiveAcceptance = when {
            // Feasible phase: descend the objective with the strategy's own feasible-phase acceptance
            // (CBLS: strict-improvement greedy) instead of the noisy feasibility-fight acceptance.
            state.cost == 0L && feasibleAcceptance != null -> feasibleAcceptance

            noiseSchedule != null && (noiseMoves.isNotEmpty() || scoreMoves.isNotEmpty()) -> {
                if (stall == null) noiseSchedule.observe(state.cost)
                acceptance.steered(noiseSchedule.level)
            }

            else -> acceptance
        }
        // The acceptance rule only reads the temperature (Metropolis); the driver advances the schedule
        // once per pick that sampled the noise pool, so acceptance stays pure. A Metropolis rule with
        // no temperature schedule falls back to a fixed conservative temperature rather than degenerating.
        val temperature = schedule.temperature?.temperature ?: DEFAULT_METROPOLIS_TEMPERATURE
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

    /** Scored value on the [scoring] basis. Weighted/raw net-delta add the objective change only once
     *  feasible (gated on `cost == 0`, so the infeasibility fight keeps the constraint gradient); the
     *  break basis already folds the shaped objective. */
    private fun score(state: LocalSearchState, move: Move): Double {
        // Feasibility-preserving objective descent (a strategy that sets [feasibleAcceptance], e.g. CBLS's
        // GreedyDescent): at cost == 0 rank by the *raw* objective delta — independent of the
        // pre-feasibility shaping lambda, which is zero under the default FeasibilityFirst — and disqualify
        // any move that re-introduces a violation. Without the disqualification a large objective reward
        // buys an infeasible single flip that then out-scores the feasibility-preserving swap and is
        // reverted every step; trading feasibility for objective is the ratchet arm's job, not this one.
        if (state.cost == 0L && feasibleAcceptance != null) {
            if (state.netDelta(move) > 0L) return Double.POSITIVE_INFINITY
            val objective = state.objective ?: return 0.0
            return state.objectiveDelta(objective, move) ?: 0.0
        }
        return when (scoring) {
            MoveScoring.Break -> state.shapedBreakScore(move)
            MoveScoring.Weighted -> state.weightedNetDelta(move) + feasibleObjectiveDelta(state, move)
            MoveScoring.Raw -> state.netDelta(move).toDouble() + feasibleObjectiveDelta(state, move)
        }
    }

    private fun feasibleObjectiveDelta(state: LocalSearchState, move: Move): Double =
        if (state.cost == 0L) state.shapedObjectiveDelta(move) else 0.0
}
