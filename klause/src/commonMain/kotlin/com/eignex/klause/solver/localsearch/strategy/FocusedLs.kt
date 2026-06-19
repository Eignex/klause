package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.movesource.ConfiguredSource
import com.eignex.klause.solver.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.solver.localsearch.schedule.Geometric
import com.eignex.klause.solver.localsearch.schedule.Schedule
import com.eignex.klause.solver.localsearch.schedule.ScheduleBundle
import kotlin.math.pow

/**
 * Focused local search: the WalkSAT/probSAT family. Each step picks a uniformly-random
 * *violated* factor, asks it for repair-move suggestions, and selects among them — the
 * "focusing" heuristic that distinguishes this family from the global, gradient-scoring
 * [Cbls].
 *
 * The **fixed-parameter** members of the family are now expressed as [SourceDrivenStrategy]
 * recipes (#721) — `{`[ViolatedRepairs]`(1)} × `[MoveScoring.Break]` × acceptance` — built by the
 * [WalkSat] / [ProbSat] / [SimulatedAnnealing] factories: WalkSAT is [AcceptanceRule.WalkSatNoise],
 * probSAT is [AcceptanceRule.ProbSat], simulated annealing is [AcceptanceRule.Metropolis].
 *
 * This class survives only for the **adaptive-parameter** variants ([WalkSat.adaptive],
 * [ProbSat.adaptive], [ProbSat.bandit]), whose scalar (`noise` / `cb`) is steered per step by a
 * [NoiseController] that observes the running cost — feedback the acceptance axis does not yet
 * consume (it lands once the engine drives a per-step/round schedule observe; see #721 follow-ups).
 * What differs between these variants is purely the **selection rule**, factored into a
 * [MoveSelection] policy ([NoiseGreedy], [ProbSatWeighted]). Configuration checking
 * ([configurationChecking], CCASat) restricts candidates to variables whose configuration changed
 * since their last flip, falling back to the full set when every candidate is CC-blocked.
 */
class FocusedLs internal constructor(
    internal val selection: MoveSelection = NoiseGreedy(),
    /** Tabu filter applied to candidate moves. */
    val tabu: TabuFilter = TabuFilter(tenure = 10),
    val configurationChecking: Boolean = false,
) : Strategy {

    override fun pickMove(state: LocalSearchState): Move? {
        val raw = state.proposeMovesFromRandomViolated() ?: return null
        val candidates = if (configurationChecking) {
            val cc = raw.filter { confChanged(state, it) }
            if (cc.isEmpty()) raw else cc
        } else {
            raw
        }
        val moves = tabu.filter(state, candidates)
        if (moves.isEmpty()) return null
        return selection.pick(state, moves)
    }

    private fun confChanged(state: LocalSearchState, move: Move): Boolean = when (move) {
        is Move.BoolFlip -> state.boolConfChange[move.varId]

        is Move.IntSet -> state.intConfChange[move.varId]

        // Compound counts as conf-changed iff *all* parts are — every affected var must have
        // moved since its last touch for the move to be eligible.
        is Move.Compound -> move.parts.all { confChanged(state, it) }
    }
}

/** Candidate-selection rule for [FocusedLs]. Receives the tabu/CC-filtered, non-empty move
 *  list and returns one. Owns its own (optional) adaptive [NoiseController]. */
internal sealed interface MoveSelection {
    fun pick(state: LocalSearchState, moves: List<Move>): Move
}

/**
 * WalkSAT selection: with probability `noise` pick a random candidate, otherwise the one with
 * the smallest shaped break score (ties broken uniformly). When [controller] is non-null the
 * noise level is steered adaptively, overriding [noise].
 */
internal class NoiseGreedy(val noise: Double = 0.5, private val controller: NoiseSchedule? = null) : MoveSelection {
    override fun pick(state: LocalSearchState, moves: List<Move>): Move {
        val n = controller?.also { it.observe(state.cost) }?.level ?: noise
        if (state.rng.nextDouble() < n) return moves[state.rng.nextInt(moves.size)]
        // Greedy on the shaped break score; under no shaping this is the raw break score.
        return requireNotNull(state.greedyPickByShapedBreak(moves))
    }
}

/**
 * probSAT selection (Balint & Schöning 2012): roulette-sample candidates with weight
 * `(eps + break)^(-cb)` — low-break candidates get exponentially more weight. Under shaping
 * the score can go negative, so the candidate set is shifted to keep the base non-negative.
 * When [controller] is non-null, `cb` is steered toward `cb·(1 - level·0.5)`: it flattens the
 * distribution (more diversification) during stalls and sharpens on improvement.
 */
internal class ProbSatWeighted(
    val cb: Double = 2.06,
    val eps: Double = 1.0,
    private val controller: NoiseSchedule? = null,
) : MoveSelection {
    override fun pick(state: LocalSearchState, moves: List<Move>): Move {
        if (moves.size == 1) return moves[0]
        val cbNow = controller?.let {
            it.observe(state.cost)
            cb * (1.0 - it.level * 0.5)
        } ?: cb
        val scores = DoubleArray(moves.size) { state.shapedBreakScore(moves[it]) }
        var minScore = scores[0]
        for (i in 1 until scores.size) if (scores[i] < minScore) minScore = scores[i]
        val shift = if (minScore < 0.0) -minScore else 0.0
        var totalWeight = 0.0
        val weights = DoubleArray(moves.size)
        for (i in moves.indices) {
            val w = (eps + scores[i] + shift).pow(-cbNow)
            weights[i] = w
            totalWeight += w
        }
        if (totalWeight == 0.0) return moves[state.rng.nextInt(moves.size)]
        var draw = state.rng.nextDouble() * totalWeight
        for (i in moves.indices) {
            draw -= weights[i]
            if (draw <= 0.0) return moves[i]
        }
        return moves[moves.size - 1]
    }
}

/** The focused source set shared by every recipe-form member of the family: a single uniformly-random
 *  violated factor's repair suggestions ([ViolatedRepairs] with `sampleCount = 1`), the WalkSAT/probSAT
 *  opener. */
private fun focusedSources() = listOf(ConfiguredSource(ViolatedRepairs.SINGLE))

/**
 * WalkSAT factory (Selman 1994). `WalkSat(...)` builds the fixed-noise variant as a
 * [SourceDrivenStrategy] recipe (`{`[ViolatedRepairs]`(1)} × `[MoveScoring.Break]` ×
 * `[AcceptanceRule.WalkSatNoise]`)`; [adaptive] builds the Hoos-2002 adaptive-noise variant, which
 * stays a [FocusedLs] until the acceptance axis can consume a per-step noise schedule. Configuration
 * checking is opt-in on both.
 */
object WalkSat {
    /** Fixed-noise WalkSAT recipe. */
    operator fun invoke(
        noise: Double = 0.5,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        configurationChecking: Boolean = false,
    ): SourceDrivenStrategy = SourceDrivenStrategy(
        sources = focusedSources(),
        scoring = MoveScoring.Break,
        acceptance = AcceptanceRule.WalkSatNoise(noise),
        tabu = tabu,
        configurationChecking = configurationChecking,
    )

    /**
     * Adaptive-noise WalkSAT: noise starts at [baselineNoise] and is steered in
     * `[baselineNoise, 1.0]` — climbs on stalls, decays on improvement. Literature reports
     * +10-30% on hard random instances over well-tuned fixed noise.
     */
    fun adaptive(
        baselineNoise: Double = 0.2,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        theta: Int = 50,
        phi: Double = 0.2,
        ewmaAlpha: Double? = null,
        configurationChecking: Boolean = false,
    ): FocusedLs = FocusedLs(
        NoiseGreedy(
            noise = baselineNoise,
            controller = NoiseController(
                initial = baselineNoise,
                theta = theta,
                phi = phi,
                minLevel = baselineNoise,
                maxLevel = 1.0,
                ewmaAlpha = ewmaAlpha,
            ),
        ),
        tabu,
        configurationChecking,
    )
}

/**
 * probSAT factory (Balint & Schöning 2012). `ProbSat(...)` builds the fixed-`cb` variant as a
 * [SourceDrivenStrategy] recipe (`{`[ViolatedRepairs]`(1)} × `[MoveScoring.Break]` ×
 * `[AcceptanceRule.ProbSat]`)`; [adaptive] / [bandit] build the variants whose `cb` is steered per
 * step, which stay a [FocusedLs] until the acceptance axis can consume a per-step noise schedule.
 * Configuration checking is opt-in on all (probSAT + CC is a strong combo on structured instances).
 */
object ProbSat {
    /** Fixed-`cb` probSAT recipe. */
    operator fun invoke(
        cb: Double = 2.06,
        eps: Double = 1.0,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        configurationChecking: Boolean = false,
    ): SourceDrivenStrategy = SourceDrivenStrategy(
        sources = focusedSources(),
        scoring = MoveScoring.Break,
        acceptance = AcceptanceRule.ProbSat(cb, eps),
        tabu = tabu,
        configurationChecking = configurationChecking,
    )

    /**
     * Adaptive-`cb` probSAT: the break-exponent starts at [baselineCb] and is steered down
     * during stalls (distribution flattens toward uniform) and back up on improvement.
     */
    fun adaptive(
        baselineCb: Double = 2.06,
        eps: Double = 1.0,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        theta: Int = 50,
        phi: Double = 0.2,
        ewmaAlpha: Double? = null,
        configurationChecking: Boolean = false,
    ): FocusedLs = FocusedLs(
        ProbSatWeighted(
            cb = baselineCb,
            eps = eps,
            controller = NoiseController(initial = 0.0, theta = theta, phi = phi, ewmaAlpha = ewmaAlpha),
        ),
        tabu,
        configurationChecking,
    )

    /**
     * Bandit-adaptive probSAT (#8): the `cb` schedule is driven by a [BanditNoiseController] — a
     * kumulant UCB1 bandit over aggressive/moderate/patient bump-on-stall profiles — instead of a
     * single fixed [NoiseController]. The bandit learns per session which schedule profile suits
     * the instance.
     */
    fun bandit(
        baselineCb: Double = 2.06,
        eps: Double = 1.0,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        seed: Long = 0L,
        configurationChecking: Boolean = false,
    ): FocusedLs = FocusedLs(
        ProbSatWeighted(
            cb = baselineCb,
            eps = eps,
            controller = BanditNoiseController.default(baseline = 0.0, seed = seed),
        ),
        tabu,
        configurationChecking,
    )
}

/**
 * Simulated-annealing factory, now a [SourceDrivenStrategy] recipe (`{`[ViolatedRepairs]`(1)} ×
 * `[MoveScoring.Break]` × `[AcceptanceRule.Metropolis]`)` — Metropolis acceptance under a cooling
 * temperature. Configuration checking is opt-in.
 */
object SimulatedAnnealing {
    /** SA recipe under a fixed geometric cooling schedule. */
    operator fun invoke(
        initialTemperature: Double = 1.0,
        coolingRate: Double = 0.999,
        minTemperature: Double = 0.001,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        configurationChecking: Boolean = false,
    ): SourceDrivenStrategy = withSchedule(
        Geometric(initialTemperature, coolingRate, minTemperature),
        tabu,
        configurationChecking,
    )

    /**
     * Build an SA recipe over an arbitrary temperature [schedule] — geometric, adaptive-cooling, or
     * reheating — so the SA arms can run any schedule through the shared [AcceptanceRule.Metropolis].
     */
    fun withSchedule(
        schedule: Schedule,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        configurationChecking: Boolean = false,
    ): SourceDrivenStrategy = SourceDrivenStrategy(
        sources = focusedSources(),
        scoring = MoveScoring.Break,
        acceptance = AcceptanceRule.Metropolis,
        schedule = ScheduleBundle(temperature = schedule),
        tabu = tabu,
        configurationChecking = configurationChecking,
    )
}
