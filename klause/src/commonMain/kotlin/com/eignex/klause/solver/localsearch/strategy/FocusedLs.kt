package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import kotlin.math.pow

/**
 * Focused local search: the WalkSAT/probSAT family. Each step picks a uniformly-random
 * *violated* factor, asks it for repair-move suggestions, and selects among them — the
 * "focusing" heuristic that distinguishes this family from the global, gradient-scoring
 * [Cbls]. What differs between the classic algorithms is purely the **selection rule**, so
 * it is factored out into a [MoveSelection] policy:
 *
 *  - [NoiseGreedy] — WalkSAT (Selman 1994): with probability `noise` take a random candidate,
 *    else the minimum-break candidate.
 *  - [ProbSatWeighted] — probSAT (Balint & Schöning 2012): roulette-sample candidates by
 *    `(eps + break)^(-cb)`, a smooth bias toward low-break moves.
 *
 * Two orthogonal refinements apply to either policy:
 *  - **Adaptive parameter** — wrap the policy's scalar (`noise` / `cb`) in a [NoiseController]
 *    so it climbs on stalls and relaxes on improvement (Hoos 2002). See [WalkSat.adaptive] /
 *    [ProbSat.adaptive].
 *  - **Configuration checking** ([configurationChecking], CCASat): restrict candidates to
 *    variables whose configuration changed since their last flip, breaking short flip-unflip
 *    cycles without a globally-disruptive tabu tenure. Falls back to the full set when every
 *    candidate is CC-blocked.
 *
 * The recognizable algorithm names survive as factory objects ([WalkSat], [ProbSat]); only
 * the strategy *type* is unified here.
 */
class FocusedLs(
    val selection: MoveSelection = NoiseGreedy(),
    val tabu: TabuFilter = TabuFilter(tenure = 10),
    val configurationChecking: Boolean = false,
) : Strategy {

    override fun pickMove(state: LocalSearchState): Move? {
        val raw = state.proposeMovesFromRandomViolated() ?: return null
        val candidates = if (configurationChecking) {
            val cc = raw.filter { confChanged(state, it) }
            if (cc.isEmpty()) raw else cc
        } else raw
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
sealed interface MoveSelection {
    fun pick(state: LocalSearchState, moves: List<Move>): Move
}

/**
 * WalkSAT selection: with probability `noise` pick a random candidate, otherwise the one with
 * the smallest shaped break score (ties broken uniformly). When [controller] is non-null the
 * noise level is steered adaptively, overriding [noise].
 */
class NoiseGreedy(
    val noise: Double = 0.5,
    private val controller: NoiseController? = null,
) : MoveSelection {
    override fun pick(state: LocalSearchState, moves: List<Move>): Move {
        val n = controller?.also { it.observe(state.cost) }?.level ?: noise
        if (state.rng.nextDouble() < n) return moves[state.rng.nextInt(moves.size)]
        // Greedy on the shaped break score; under no shaping this is the raw break score.
        return state.greedyPickByShapedBreak(moves)!!
    }
}

/**
 * probSAT selection (Balint & Schöning 2012): roulette-sample candidates with weight
 * `(eps + break)^(-cb)` — low-break candidates get exponentially more weight. Under shaping
 * the score can go negative, so the candidate set is shifted to keep the base non-negative.
 * When [controller] is non-null, `cb` is steered toward `cb·(1 - level·0.5)`: it flattens the
 * distribution (more diversification) during stalls and sharpens on improvement.
 */
class ProbSatWeighted(
    val cb: Double = 2.06,
    val eps: Double = 1.0,
    private val controller: NoiseController? = null,
) : MoveSelection {
    override fun pick(state: LocalSearchState, moves: List<Move>): Move {
        if (moves.size == 1) return moves[0]
        val cbNow = controller?.let { it.observe(state.cost); cb * (1.0 - it.level * 0.5) } ?: cb
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

/**
 * WalkSAT factory (Selman 1994). `WalkSat(...)` builds a fixed-noise [FocusedLs];
 * [adaptive] builds the Hoos-2002 adaptive-noise variant. Configuration checking is opt-in
 * on both.
 */
object WalkSat {
    operator fun invoke(
        noise: Double = 0.5,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        configurationChecking: Boolean = false,
    ): FocusedLs = FocusedLs(NoiseGreedy(noise), tabu, configurationChecking)

    /**
     * Adaptive-noise WalkSAT: noise starts at [baselineNoise] and is steered in
     * `[baselineNoise, 1.0]` — climbs on stalls, decays on improvement. Literature reports
     * +10-30% on hard random instances over well-tuned fixed noise.
     *
     * @param ewmaAlpha opt-in EWMA improvement detection (smoothed average vs all-time low).
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
                initial = baselineNoise, theta = theta, phi = phi,
                minLevel = baselineNoise, maxLevel = 1.0, ewmaAlpha = ewmaAlpha,
            ),
        ),
        tabu, configurationChecking,
    )
}

/**
 * probSAT factory (Balint & Schöning 2012). `ProbSat(...)` builds a fixed-`cb` [FocusedLs];
 * [adaptive] builds the variant whose `cb` relaxes on stalls. Configuration checking is opt-in
 * on both (probSAT + CC is a strong combo on structured instances).
 */
object ProbSat {
    operator fun invoke(
        cb: Double = 2.06,
        eps: Double = 1.0,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        configurationChecking: Boolean = false,
    ): FocusedLs = FocusedLs(ProbSatWeighted(cb, eps), tabu, configurationChecking)

    /**
     * Adaptive-`cb` probSAT: the break-exponent starts at [baselineCb] and is steered down
     * during stalls (distribution flattens toward uniform) and back up on improvement.
     *
     * @param ewmaAlpha opt-in EWMA improvement detection; see [WalkSat.adaptive].
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
        tabu, configurationChecking,
    )
}
