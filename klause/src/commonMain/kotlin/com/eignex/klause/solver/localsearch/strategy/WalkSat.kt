package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * WalkSAT extended to mixed Boolean/integer moves. Pick a violated factor uniformly, ask it
 * for repair-move suggestions, then either flip a random suggestion (probability [noise]) or
 * pick the suggestion with the smallest break count (ties broken uniformly at random).
 *
 * Two orthogonal, opt-in refinements collapse the former `AdaptiveWalkSat` / `CcaWalkSat`
 * variants into knobs on this one class:
 *
 *  - **Adaptive noise** ([noiseController] non-null, Hoos 2002): the noise level climbs during
 *    stalls and decays on improvement instead of staying fixed. Use [adaptive] to build one.
 *  - **Configuration checking** ([configurationChecking], CCASat-style): candidate moves are
 *    first restricted to variables whose configuration changed since their last flip (see
 *    [LocalSearchState.boolConfChange] / [LocalSearchState.intConfChange]), breaking short
 *    flip-unflip cycles without a globally-disruptive tabu tenure. Falls back to the full set
 *    when every candidate is CC-blocked, mirroring the all-tabu aspiration.
 *
 * The two combine freely — adaptive noise *and* configuration checking together is the
 * canonical CCASat regime and was previously inexpressible.
 *
 * Short-term tabu filtering, aspiration, and dynamic tenure are delegated to [tabu]; see
 * [TabuFilter]. Default: tenure 10 with the "drop the filter when every candidate is tabu"
 * aspiration.
 */
class WalkSat(
    val noise: Double = 0.5,
    val tabu: TabuFilter = TabuFilter(tenure = 10),
    /** Non-null ⇒ adaptive noise: the controller is consulted each step and its level
     *  overrides [noise]. null ⇒ fixed [noise]. Stateful — one controller per strategy
     *  instance (same lifecycle as the old AdaptiveWalkSat field). */
    private val noiseController: NoiseController? = null,
    /** CCASat-style configuration checking on the candidate set. */
    val configurationChecking: Boolean = false,
) : Strategy {

    /** Current noise level, for tests / observability; not part of the Strategy API. */
    val currentNoise: Double get() = noiseController?.level ?: noise

    override fun pickMove(state: LocalSearchState): Move? {
        val raw = state.proposeMovesFromRandomViolated() ?: return null
        val candidates = if (configurationChecking) {
            val cc = raw.filter { confChanged(state, it) }
            if (cc.isEmpty()) raw else cc
        } else raw
        val moves = tabu.filter(state, candidates)
        val noiseNow = noiseController?.also { it.observe(state.cost) }?.level ?: noise
        if (state.rng.nextDouble() < noiseNow) {
            return moves[state.rng.nextInt(moves.size)]
        }
        // Greedy pick on the shaped break score; under no shaping this is identical to
        // picking on the raw integer break score.
        return state.greedyPickByShapedBreak(moves)
    }

    private fun confChanged(state: LocalSearchState, move: Move): Boolean = when (move) {
        is Move.BoolFlip -> state.boolConfChange[move.varId]
        is Move.IntSet -> state.intConfChange[move.varId]
        // Compound counts as conf-changed iff *all* parts are — every affected var must have
        // moved since its last touch for the move to be eligible.
        is Move.Compound -> move.parts.all { confChanged(state, it) }
    }

    companion object {
        /**
         * Adaptive-noise WalkSAT (Hoos 2002). Noise starts at [baselineNoise] and is steered
         * by a [NoiseController] in `[baselineNoise, 1.0]` — climbs on stalls, decays on
         * improvement. Literature reports +10-30% on hard random instances over a well-tuned
         * fixed-noise WalkSat. Optionally combine with [configurationChecking].
         *
         * @param ewmaAlpha opt-in EWMA improvement detection (smoothed average instead of the
         *   all-time low); see [NoiseController].
         */
        fun adaptive(
            baselineNoise: Double = 0.2,
            tabu: TabuFilter = TabuFilter(tenure = 10),
            theta: Int = 50,
            phi: Double = 0.2,
            ewmaAlpha: Double? = null,
            configurationChecking: Boolean = false,
        ): WalkSat = WalkSat(
            noise = baselineNoise,
            tabu = tabu,
            noiseController = NoiseController(
                initial = baselineNoise,
                theta = theta,
                phi = phi,
                minLevel = baselineNoise,
                maxLevel = 1.0,
                ewmaAlpha = ewmaAlpha,
            ),
            configurationChecking = configurationChecking,
        )
    }
}
