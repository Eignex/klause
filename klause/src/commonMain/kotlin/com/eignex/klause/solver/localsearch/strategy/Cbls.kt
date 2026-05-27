package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Constraint-Based Local Search strategy. Unlike SAT-family strategies ([ProbSat],
 * [WalkSat], [Ddfw]) that route picks through a randomly-chosen violated factor, CBLS
 * scores moves against a *global* weighted-violation gradient:
 *
 *   `score(move) = Σ factorWeights[f] · Δviolated[f] + shapingLambda · Δobjective`
 *
 * The strategy works *regardless* of feasibility: at violation it pulls candidate moves
 * from violated factors (via `proposeRepairMoves`), at feasibility it pulls them from
 * satisfied factors (via `proposeStructuredMoves`) augmented with single-variable
 * objective-direction moves so the descent has candidates even when no factor proposes
 * a useful one.
 *
 * Per step:
 *  1. If the previous step didn't strictly improve (no progress for [stallSteps]):
 *     bump weights on currently-violated factors by [stallIncrement] — SAPS-style scale,
 *     amplifying pressure on factors that resist being repaired.
 *  2. Collect candidate moves:
 *     - From each violated factor (capped at [violatedSampleCount]): `proposeRepairMoves`.
 *     - From each currently-satisfied factor (capped at [satisfiedSampleCount]):
 *       `proposeStructuredMoves`.
 *     - Plus seed single-variable moves on the objective's nonzero-weight Bool/Int vars
 *       so a flat constraint landscape still has objective-direction candidates.
 *  3. Score each by [LocalSearchState.weightedNetDelta] + [LocalSearchState.shapedObjectiveDelta].
 *     Pick the minimum (with [noiseProbability] random pick for diversification).
 *  4. Tabu filter via [tabu]; [AspirationCriterion.OrImproving] admits strict
 *     weighted-improvement moves.
 *
 * Defaults match the yuck-style "moderate noise, gentle stall pressure" regime that
 * generalises across CP shapes. Tune [shapingLambda] upward on objective-heavy problems
 * where the constraint gradient dominates; tune [stallIncrement] up on plateau-heavy
 * landscapes.
 */
class Cbls(
    val noiseProbability: Double = 0.05,
    /** Steps without strict improvement before weights are bumped on violated factors. */
    val stallSteps: Int = 1,
    /** Per-bump weight increment for violated factors. */
    val stallIncrement: Double = 1.0,
    /** Cap on violated factors sampled per [pickMove] call for candidate generation. */
    val violatedSampleCount: Int = 4,
    /** Cap on satisfied factors sampled per [pickMove] call (for `proposeStructuredMoves`). */
    val satisfiedSampleCount: Int = 4,
    val tabu: TabuFilter = TabuFilter.Disabled,
) : Strategy {

    init {
        require(noiseProbability in 0.0..1.0) { "noiseProbability ∈ [0, 1], got $noiseProbability" }
        require(stallSteps >= 1) { "stallSteps ≥ 1, got $stallSteps" }
        require(stallIncrement > 0) { "stallIncrement > 0, got $stallIncrement" }
        require(violatedSampleCount >= 1) { "violatedSampleCount ≥ 1, got $violatedSampleCount" }
        require(satisfiedSampleCount >= 0) { "satisfiedSampleCount ≥ 0, got $satisfiedSampleCount" }
    }

    private var lastImprovingStep: Long = -1L
    private var lastSeenStep: Long = -1L
    private var lastCost: Int = Int.MAX_VALUE

    override fun pickMove(state: LocalSearchState): Move? {
        // Stall detection: when [state.cost] hasn't strictly decreased for [stallSteps]
        // applied moves, bump weights. Reads the engine-maintained step counter so we
        // don't need our own apply-tracking — `state.step` advances on every committed
        // move regardless of strategy.
        if (state.step != lastSeenStep) {
            if (state.cost < lastCost) {
                lastImprovingStep = state.step
                lastCost = state.cost
            } else if (state.step - lastImprovingStep >= stallSteps) {
                bumpViolatedWeights(state, stallIncrement)
                lastImprovingStep = state.step  // reset stall window after the bump
            }
            lastSeenStep = state.step
        }

        // Candidate generation: violated factors' repairs + satisfied factors' structured
        // moves + objective-direction seed moves. Each source contributes a bounded number
        // of moves so the per-step cost is O(arity × cap), not O(numFactors × numVars).
        val sink = state.moveSink
        sink.clear()
        sampleFromViolated(state, sink)
        sampleFromSatisfied(state, sink)
        seedObjectiveMoves(state, sink)

        val raw = sink.list
        if (raw.isEmpty()) return null
        val moves = tabu.filter(state, raw)
        if (moves.isEmpty()) return null

        if (state.rng.nextDouble() < noiseProbability) {
            return moves[state.rng.nextInt(moves.size)]
        }

        var bestMove: Move = moves[0]
        var bestScore = score(state, bestMove)
        var tieCount = 1
        for (i in 1 until moves.size) {
            val s = score(state, moves[i])
            if (s < bestScore) {
                bestMove = moves[i]; bestScore = s; tieCount = 1
            } else if (s == bestScore) {
                tieCount++
                if (state.rng.nextInt(tieCount) == 0) bestMove = moves[i]
            }
        }
        return bestMove
    }

    private fun score(state: LocalSearchState, move: Move): Double =
        state.weightedNetDelta(move) + state.shapedObjectiveDelta(move)

    /** Bump weights on every currently-violated factor by [increment]. SAPS-style scale
     *  rather than DDFW-style transfer — we don't redistribute from satisfied neighbors,
     *  we just inject pressure. This is the local-minimum signal: "these constraints have
     *  resisted being fixed, prioritize them". */
    private fun bumpViolatedWeights(state: LocalSearchState, increment: Double) {
        val w = state.factorWeights
        val violatedSnapshot = state.violated.toIntArray()
        for (fid in violatedSnapshot) w[fid] += increment
    }

    private fun sampleFromViolated(state: LocalSearchState, sink: com.eignex.klause.solver.localsearch.MoveSink) {
        if (state.violated.isEmpty()) return
        repeat(minOf(violatedSampleCount, state.violated.size)) {
            val fid = state.violated.random(state.rng)
            state.factors[fid].proposeRepairMoves(state, fid, sink)
        }
    }

    private fun sampleFromSatisfied(state: LocalSearchState, sink: com.eignex.klause.solver.localsearch.MoveSink) {
        if (satisfiedSampleCount == 0) return
        val total = state.problem.numFactors
        if (total == 0) return
        // Random sampling rather than enumerate-then-filter; the satisfied-vs-violated
        // index isn't materialised, so a 4-random-probe-of-N approach is cheaper than
        // walking everything.
        repeat(satisfiedSampleCount) {
            val fid = state.rng.nextInt(total)
            if (!state.violated.contains(fid)) {
                state.factors[fid].proposeStructuredMoves(state, fid, sink)
            }
        }
    }

    /** Seed single-variable moves directly on the objective's nonzero-weight vars. Without
     *  this, a fully-satisfied state with no factor proposing structured moves has zero
     *  candidates and pickMove returns null — engine restarts spuriously. */
    private fun seedObjectiveMoves(state: LocalSearchState, sink: com.eignex.klause.solver.localsearch.MoveSink) {
        val obj = state.objective ?: return
        when (obj) {
            is com.eignex.klause.solver.LinearObjective -> {
                for (v in obj.boolWeights.indices) {
                    if (obj.boolWeights[v] == 0.0) continue
                    sink.addBoolFlip(v)
                }
                for (v in obj.intCoefficients.indices) {
                    if (obj.intCoefficients[v] == 0.0) continue
                    val cur = state.assignment.intValue(v)
                    val d = state.problem.intDomains[v]
                    // Step in the direction the coefficient says reduces the objective.
                    if (obj.intCoefficients[v] > 0 && cur > d.min) sink.addIntSet(v, cur - 1)
                    if (obj.intCoefficients[v] < 0 && cur < d.max) sink.addIntSet(v, cur + 1)
                }
            }
            else -> { /* no per-var direction without inspecting the objective shape */ }
        }
    }
}
