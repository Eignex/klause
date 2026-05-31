package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchFactor
import com.eignex.klause.solver.localsearch.LocalSearchState

/**
 * Constraint-Based Local Search strategy. Unlike SAT-family strategies ([ProbSat],
 * [WalkSat]) that route picks through a randomly-chosen violated factor, CBLS
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
 *     amplifying pressure on factors that resist being repaired. Then, with probability
 *     [smoothProb], apply SAPS-style probabilistic *smoothing*: pull every weight a fraction
 *     [smoothFactor] of the way back toward [baseWeight]. Smoothing is a forgetting mechanism
 *     that counteracts the otherwise-monotone weight growth, so factors that are no longer
 *     hard decay back and the gradient doesn't ossify on long plateau-heavy runs. Disabled by
 *     default ([smoothProb] = 0.0); the bump-only schedule is the baseline regime.
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
    /** Probability of applying a smoothing pass after a stall bump. `0.0` (default) disables
     *  smoothing entirely, leaving the monotone bump-only schedule. */
    val smoothProb: Double = 0.0,
    /** Smoothing pull strength: each smoothed weight moves this fraction of the way toward
     *  [baseWeight] (`w ← (1 - smoothFactor)·w + smoothFactor·baseWeight`). Only consulted
     *  when [smoothProb] > 0. */
    val smoothFactor: Double = 0.8,
    /** Weight that smoothing pulls toward — the lazily-allocated default of [factorWeights]. */
    val baseWeight: Double = 1.0,
    /** Cap on violated factors sampled per [pickMove] call for candidate generation. */
    val violatedSampleCount: Int = 4,
    /** Cap on satisfied factors sampled per [pickMove] call (for `proposeStructuredMoves`). */
    val satisfiedSampleCount: Int = 4,
    /** Steps without a strict *cost drop* before the **plateau-escape** machinery engages. At
     *  infeasibility the candidate pool is only violated factors' own repair moves; when that
     *  pool traps the search in a local minimum, this kicks in [frontierMoveCap] frontier moves
     *  (on variables of factors *neighbouring* the violated ones) and raises the effective noise
     *  to [stallNoise] so the search can step uphill out of the basin. `0` disables escape. */
    val frontierAfterStall: Int = 40,
    /** Cap on frontier (neighbour-variable) moves injected per stalled [pickMove]. */
    val frontierMoveCap: Int = 32,
    /** Effective [noiseProbability] while stalled past [frontierAfterStall] — a random-walk
     *  diversification over the broadened pool that lets strict local minima be escaped. */
    val stallNoise: Double = 0.4,
    val tabu: TabuFilter = TabuFilter.Disabled,
) : Strategy {

    init {
        require(noiseProbability in 0.0..1.0) { "noiseProbability ∈ [0, 1], got $noiseProbability" }
        require(stallSteps >= 1) { "stallSteps ≥ 1, got $stallSteps" }
        require(stallIncrement > 0) { "stallIncrement > 0, got $stallIncrement" }
        require(smoothProb in 0.0..1.0) { "smoothProb ∈ [0, 1], got $smoothProb" }
        require(smoothFactor in 0.0..1.0) { "smoothFactor ∈ [0, 1], got $smoothFactor" }
        require(baseWeight > 0) { "baseWeight > 0, got $baseWeight" }
        require(violatedSampleCount >= 1) { "violatedSampleCount ≥ 1, got $violatedSampleCount" }
        require(satisfiedSampleCount >= 0) { "satisfiedSampleCount ≥ 0, got $satisfiedSampleCount" }
        require(frontierAfterStall >= 0) { "frontierAfterStall ≥ 0, got $frontierAfterStall" }
        require(frontierMoveCap >= 1) { "frontierMoveCap ≥ 1, got $frontierMoveCap" }
        require(stallNoise in 0.0..1.0) { "stallNoise ∈ [0, 1], got $stallNoise" }
    }

    private var lastImprovingStep: Long = -1L
    private var lastSeenStep: Long = -1L
    private var lastCost: Long = Long.MAX_VALUE
    /** Step of the last strict cost decrease — unlike [lastImprovingStep] this is *not* reset
     *  by a weight bump, so it measures a true "no progress" stall window for plateau escape. */
    private var lastDropStep: Long = 0L

    override fun pickMove(state: LocalSearchState): Move? {
        // Stall detection: when [state.cost] hasn't strictly decreased for [stallSteps]
        // applied moves, bump weights. Reads the engine-maintained step counter so we
        // don't need our own apply-tracking — `state.step` advances on every committed
        // move regardless of strategy.
        if (state.step < lastSeenStep) {
            // step rewound → a restart happened; reset the stall trackers to this epoch.
            lastImprovingStep = state.step
            lastDropStep = state.step
            lastCost = state.cost
        }
        if (state.step != lastSeenStep) {
            if (state.cost < lastCost) {
                lastImprovingStep = state.step
                lastCost = state.cost
                lastDropStep = state.step
            } else if (state.step - lastImprovingStep >= stallSteps) {
                bumpViolatedWeights(state, stallIncrement)
                if (smoothProb > 0.0 && state.rng.nextDouble() < smoothProb) smoothAllWeights(state)
                lastImprovingStep = state.step  // reset stall window after the bump
            }
            lastSeenStep = state.step
        }

        // Plateau escape: when no strict cost drop for [frontierAfterStall] steps, the search
        // is trapped — the violated-only candidate pool is too small for noise/weight-bumping
        // to escape. Broaden the pool with frontier (neighbour) moves and raise noise.
        val stalled = frontierAfterStall > 0 &&
            state.cost > 0 && state.step - lastDropStep >= frontierAfterStall

        // Candidate generation: violated factors' repairs + satisfied factors' structured
        // moves + objective-direction seed moves. Each source contributes a bounded number
        // of moves so the per-step cost is O(arity × cap), not O(numFactors × numVars).
        val sink = state.moveSink
        sink.clear()
        sampleFromViolated(state, sink)
        if (stalled) sampleFrontier(state, sink)
        sampleFromSatisfied(state, sink)
        seedObjectiveMoves(state, sink)

        val raw = sink.list
        if (raw.isEmpty()) return null
        val filtered = tabu.filter(state, raw)
        // While stalled, never let tabu starve the pool into a null move (which forces a
        // full restart and discards plateau progress): fall back to the unfiltered candidates
        // so the search keeps walking the plateau. Off-stall, an empty tabu pool still yields
        // null (the normal "let the engine restart" path).
        val moves = if (filtered.isEmpty()) {
            if (stalled) raw else return null
        } else {
            filtered
        }

        val effectiveNoise = if (stalled) stallNoise else noiseProbability
        if (state.rng.nextDouble() < effectiveNoise) {
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

    /** Score a candidate move. **Feasibility-first**: the objective component is gated
     *  behind `state.cost == 0`. At infeasibility we ignore the objective entirely so the
     *  search isn't pulled away from constraint satisfaction by a competing gradient —
     *  pure weighted-violation delta wins. At feasibility the objective is the only
     *  signal that distinguishes the equally-cost-0 candidates, so it fully drives. */
    private fun score(state: LocalSearchState, move: Move): Double {
        val violationDelta = state.weightedNetDelta(move)
        val objDelta = if (state.cost == 0L) state.shapedObjectiveDelta(move) else 0.0
        return violationDelta + objDelta
    }

    /** Bump weights on every currently-violated factor by [increment]. SAPS-style scale
     *  rather than DDFW-style transfer — we don't redistribute from satisfied neighbors,
     *  we just inject pressure. This is the local-minimum signal: "these constraints have
     *  resisted being fixed, prioritize them". */
    private fun bumpViolatedWeights(state: LocalSearchState, increment: Double) {
        val w = state.factorWeights
        val violatedSnapshot = state.violated.toIntArray()
        for (fid in violatedSnapshot) w[fid] += increment
    }

    /** SAPS-style probabilistic smoothing (forgetting): pull every factor weight a fraction
     *  [smoothFactor] of the way back toward [baseWeight]. [bumpViolatedWeights] only ever
     *  grows weights, so without a counter-pressure the gradient ossifies on long runs;
     *  smoothing lets weight on factors that are no longer hard decay back. Called with
     *  probability [smoothProb] right after a stall bump. */
    private fun smoothAllWeights(state: LocalSearchState) {
        val w = state.factorWeights
        val keep = 1.0 - smoothFactor
        val pull = smoothFactor * baseWeight
        for (i in w.indices) w[i] = keep * w[i] + pull
    }

    private fun sampleFromViolated(state: LocalSearchState, sink: com.eignex.klause.solver.localsearch.MoveSink) {
        if (state.violated.isEmpty()) return
        repeat(minOf(violatedSampleCount, state.violated.size)) {
            val fid = state.violated.random(state.rng)
            state.factors[fid].proposeRepairMoves(state, fid, sink)
        }
    }

    /** **Frontier moves** for plateau escape: when the search is trapped, the violated-only
     *  repair pool can't get out — every repair of a violated factor breaks a *satisfied
     *  neighbour*, and the moves that would first re-arrange those neighbours are never
     *  generated (satisfied factors are sampled only at feasibility). This injects bounded
     *  ±1 / bool-flip moves on the variables of factors that *neighbour* a violated factor
     *  (share a variable), giving the search — together with the raised stall noise — moves
     *  to step through the basin wall. Capped at [frontierMoveCap] per call. */
    private fun sampleFrontier(state: LocalSearchState, sink: com.eignex.klause.solver.localsearch.MoveSink) {
        if (state.violated.isEmpty()) return
        val problem = state.problem
        var budget = frontierMoveCap
        repeat(minOf(violatedSampleCount, state.violated.size)) {
            if (budget <= 0) return
            val fid = state.violated.random(state.rng)
            val f = state.factors[fid]
            for (v in f.intVars) {
                for (nf in problem.intOccurrences[v]) {
                    if (nf == fid) continue
                    budget = addNeighbourMoves(state, sink, nf, budget)
                    if (budget <= 0) return
                }
            }
            for (v in f.boolVars) {
                for (nf in problem.boolOccurrences[v]) {
                    if (nf == fid) continue
                    budget = addNeighbourMoves(state, sink, nf, budget)
                    if (budget <= 0) return
                }
            }
        }
    }

    /** Emit ±1 int-steps and bool flips for every variable of factor [nf], spending from and
     *  returning the remaining [budget]. */
    private fun addNeighbourMoves(
        state: LocalSearchState, sink: com.eignex.klause.solver.localsearch.MoveSink, nf: Int, budget: Int,
    ): Int {
        var b = budget
        val nfac = state.factors[nf]
        for (u in nfac.intVars) {
            if (b <= 0) return b
            val cur = state.assignment.intValue(u)
            val d = state.problem.intDomains[u]
            if (cur < d.max) { sink.addChannelingIntSet(state, u, cur + 1); b-- }
            if (b <= 0) return b
            if (cur > d.min) { sink.addChannelingIntSet(state, u, cur - 1); b-- }
        }
        for (u in nfac.boolVars) {
            if (b <= 0) return b
            sink.addBoolFlip(u); b--
        }
        return b
    }

    private fun sampleFromSatisfied(state: LocalSearchState, sink: com.eignex.klause.solver.localsearch.MoveSink) {
        if (satisfiedSampleCount == 0) return
        // At infeasibility the structured-move source contributes nothing useful — its
        // moves only matter when the engine is already at cost==0 and looking for
        // objective-improving steps. Sampling here just dilutes the candidate pool while
        // the search should be focused on closing violations.
        if (state.cost > 0) return
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
     *  candidates and pickMove returns null — engine restarts spuriously. Skipped at
     *  infeasibility for the same reason as [sampleFromSatisfied]: the objective gradient
     *  doesn't matter when we're still chasing violations, and the engine has
     *  proposeRepairMoves to cover that phase. */
    private fun seedObjectiveMoves(state: LocalSearchState, sink: com.eignex.klause.solver.localsearch.MoveSink) {
        if (state.cost > 0) return
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
                    // Channeling-aware so int-move + indicator updates stay atomic.
                    if (obj.intCoefficients[v] > 0 && cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
                    if (obj.intCoefficients[v] < 0 && cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
                }
            }
            else -> { /* no per-var direction without inspecting the objective shape */ }
        }
    }
}
