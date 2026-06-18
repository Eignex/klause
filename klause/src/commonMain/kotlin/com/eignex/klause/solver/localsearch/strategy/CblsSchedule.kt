package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.localsearch.movesource.EjectionChains
import com.eignex.klause.solver.localsearch.movesource.Frontier
import com.eignex.klause.solver.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.solver.localsearch.movesource.SatisfiedStructured
import com.eignex.klause.solver.localsearch.movesource.StallKick
import com.eignex.klause.solver.localsearch.movesource.StallSwaps
import com.eignex.klause.solver.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.solver.localsearch.schedule.WeightSchedule

/**
 * The Constraint-Based Local Search orchestration as the [SourceDrivenStrategy]'s **stall-schedule
 * mode** (#721). Where the driver's base mode is a flat collect→score→accept loop, CBLS scores moves
 * against a *global* weighted-violation gradient and is governed by a stateful stall schedule that
 * the flat loop cannot express:
 *
 *   `score(move) = Σ factorWeights(f) · Δviolated(f) + shapingLambda · Δobjective`
 *
 * The generation is the shared catalog ([ViolatedRepairs], [Frontier], [SatisfiedStructured],
 * [ObjectiveSeed], [StallSwaps], [EjectionChains], [StallKick]); what lives here is the schedule that
 * decides *which* sources fire *when* and how the result is selected:
 *
 *  1. **Adaptive weights.** When `state.cost` hasn't strictly improved for [stallSteps] applied moves,
 *     bump every currently-violated factor by [stallIncrement] (SAPS-style pressure on resistant
 *     constraints) and, with probability [smoothProb], smooth every weight a fraction [smoothFactor]
 *     back toward its seeded baseline scaled by [baseWeight] (the forgetting mechanism).
 *  2. **Candidate generation.** Violated-factor repairs always; at feasibility the satisfied-factor
 *     structured moves and objective-direction seeds; at infeasibility the elected implicit globals.
 *     When stalled past [frontierAfterStall] (no strict cost drop), broaden with frontier moves and
 *     score-only stall swaps / ejection chains.
 *  3. **Selection.** Greedy on the `scoring` basis over both pools with a uniform tie-break, or — with
 *     the stall-aware noise (raised to [stallNoise] while stalled) — a random diversification draw
 *     from the noise-eligible pool (restricted to primitive moves while stall swaps/chains are armed).
 *  4. **Targeted kick.** After [stallKickAfter] certified-stuck steps, inject one bounded [StallKick]
 *     perturbation and restart the descent clock.
 *
 * The arm learns and prunes nothing, so it carries no soundness obligation; the `deltaIf*` probes it
 * relies on are guarded by the existing delta-consistency oracle.
 */
@Suppress("LongParameterList") // the CBLS schedule's tuning surface; defaults are the tuned regime
class CblsSchedule(
    private val noiseProbability: Double,
    private val stallSteps: Int,
    private val stallIncrement: Double,
    private val smoothProb: Double,
    private val smoothFactor: Double,
    private val baseWeight: Double,
    private val violatedSampleCount: Int,
    private val satisfiedSampleCount: Int,
    private val frontierAfterStall: Int,
    private val frontierMoveCap: Int,
    private val stallNoise: Double,
    private val stallSwapCap: Int,
    private val stallChainCap: Int,
    private val stallChainDepth: Int,
    private val stallKickAfter: Int,
    private val stallKickVars: Int,
    private val implicitStructuredCap: Int,
) {

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
        require(stallSwapCap >= 0) { "stallSwapCap ≥ 0, got $stallSwapCap" }
        require(stallChainCap >= 0) { "stallChainCap ≥ 0, got $stallChainCap" }
        require(stallChainDepth >= 2) { "stallChainDepth ≥ 2, got $stallChainDepth" }
        require(stallKickAfter >= 0) { "stallKickAfter ≥ 0, got $stallKickAfter" }
        require(stallKickVars >= 1) { "stallKickVars ≥ 1, got $stallKickVars" }
        require(implicitStructuredCap >= 0) { "implicitStructuredCap ≥ 0, got $implicitStructuredCap" }
    }

    /** The CBLS SAPS-style bump + probabilistic smoothing, unified with FeasibilityJump's decay
     *  family ([WeightSchedule]); bit-for-bit equal to the former inline maintenance for the
     *  production smoothing rates. */
    private val weightSchedule = WeightSchedule.cbls(
        stallSteps = stallSteps,
        stallIncrement = stallIncrement,
        smoothProb = smoothProb,
        smoothFactor = smoothFactor,
        baseWeight = baseWeight,
    )

    private var lastSeenStep: Long = -1L
    private var lastCost: Long = Long.MAX_VALUE

    /** Step of the last strict cost decrease — unlike the weight schedule's own stall (reset by a
     *  bump) this is *not* reset by a bump, so it measures a true "no progress" window for escape. */
    private var lastDropStep: Long = 0L

    /** Pick the next move under the CBLS stall schedule, scored on the `scoring` basis and
     *  tabu-filtered by `tabu`; `null` when no candidate is available (the engine restarts). */
    internal fun pickMove(state: LocalSearchState, scoring: MoveScoring, tabu: TabuFilter): Move? {
        // Adaptive weights: the unified schedule bumps violated factors on a [stallSteps] stall and
        // probabilistically smooths back toward the seeded baseline. Reads the engine-maintained step
        // counter so we don't need our own apply-tracking — `state.step` advances on every committed
        // move regardless of strategy.
        weightSchedule.maintain(
            state.step,
            state.cost,
            state.factorWeights,
            state.baseFactorWeights,
            state.violated.toIntArray(),
            state.rng,
        )
        // Separate stall-window tracker for plateau escape: it resets only on a strict cost drop (and
        // a restart's rewound step), so it measures a true no-progress window, distinct from the
        // weight schedule's own bump-resetting stall.
        if (state.step < lastSeenStep) {
            lastDropStep = state.step
            lastCost = state.cost
            lastSeenStep = state.step
        } else if (state.step != lastSeenStep) {
            if (state.cost < lastCost) {
                lastCost = state.cost
                lastDropStep = state.step
            }
            lastSeenStep = state.step
        }

        // Targeted kick escalation (see [stallKickAfter]): the search has been certified stuck for
        // a window long enough that chains/swaps/noise have all had their chance — perturb the
        // violated factor's local cone and restart the descent clock.
        if (stallKickAfter > 0 && state.cost > 0 && state.step - lastDropStep >= stallKickAfter) {
            val kick = buildStallKick(state)
            if (kick != null) {
                lastDropStep = state.step // fresh descent window after the perturbation
                return kick
            }
        }

        // Plateau escape: when no strict cost drop for [frontierAfterStall] steps, the search is
        // trapped — the violated-only candidate pool is too small for noise/weight-bumping to
        // escape. Broaden the pool with frontier (neighbour) moves and raise noise.
        val stalled = frontierAfterStall > 0 &&
            state.cost > 0 && state.step - lastDropStep >= frontierAfterStall

        // Candidate generation: violated factors' repairs + satisfied factors' structured moves +
        // objective-direction seed moves. Each source contributes a bounded number of moves so the
        // per-step cost is O(arity × cap), not O(numFactors × numVars).
        val sink = state.moveSink
        sink.clear()
        sampleFromViolated(state, sink)
        if (stalled) sampleFrontier(state, sink)
        sampleFromSatisfied(state, sink)
        if (state.cost > 0L) sampleElectedStructured(state, sink)
        seedObjectiveMoves(state, sink)

        // Stall swaps and ejection chains live in a private sink: they compete on *score only*,
        // never in the noise draw. Random-picking a multi-var coordinated move is a large
        // destructive perturbation (measured on bacp: min 7 → 67); score-picked, the same moves are
        // exactly the coordinated escape the reification plateaus need (bacp/curriculum solve). All
        // other candidates — including factor-proposed compounds — keep the original noise-eligible
        // behavior; restricting those too regressed the instances whose diversification relied on
        // them (rcpsp-wet, spot5, tpp).
        swapSink.clear()
        if (stalled) {
            sampleStallSwaps(state, swapSink)
            sampleStallChains(state, swapSink)
        }

        var raw = sink.list
        if (raw.isEmpty() && swapSink.list.isEmpty() && !stalled) {
            // Starvation fallback: under per-move invariants a violated factor whose repair
            // proposals all target *defined* vars contributes nothing — on reified-heavy models
            // (every indicator bool defined) whole picks come up empty, the engine restarts, and
            // because null picks never advance `state.step` the stall machinery that would broaden
            // the pool never engages (measured on prize-collecting: restart churn at cost ≈166 under
            // invariants). Broaden immediately instead: frontier moves reach the searched neighbour
            // vars, and chains (when enabled) walk the break structure from them.
            sampleFrontier(state, sink)
            sampleStallChains(state, swapSink)
            raw = sink.list
        }
        val swaps = swapSink.list
        if (raw.isEmpty() && swaps.isEmpty()) return null
        val filtered = tabu.filter(state, raw)
        // While stalled, never let tabu starve the pool into a null move (which forces a full
        // restart and discards plateau progress): fall back to the unfiltered candidates so the
        // search keeps walking the plateau. Off-stall, an empty tabu pool still yields null (the
        // normal "let the engine restart" path).
        val moves = if (filtered.isEmpty()) {
            if (stalled) raw else return null
        } else {
            filtered
        }

        val effectiveNoise = if (stalled) stallNoise else noiseProbability
        if (moves.isNotEmpty() && state.rng.nextDouble() < effectiveNoise) {
            // Plateau-buster half 2 (see [stallSwapCap]): while stalled, draw noise from primitive
            // moves only. Stalled noise runs hot (stallNoise = 0.4) and at that rate random compound
            // picks are a large destructive perturbation — measured on bacp they thrash the plateau
            // — while the same compounds score-picked are the escape mechanism. Gated with the swaps
            // because alone it starves landscapes whose diversification relies on randomly-taken
            // factor compounds.
            val pool = if (stalled && (stallSwapCap > 0 || stallChainCap > 0)) {
                moves.filterNot { it is Move.Compound }.ifEmpty { moves }
            } else {
                moves
            }
            return pool[state.rng.nextInt(pool.size)]
        }

        var bestMove: Move? = null
        var bestScore = Double.POSITIVE_INFINITY
        var tieCount = 0
        for (pool in arrayOf(moves, swaps)) {
            for (m in pool) {
                val s = score(scoring, state, m)
                if (s < bestScore) {
                    bestMove = m
                    bestScore = s
                    tieCount = 1
                } else if (s == bestScore) {
                    tieCount++
                    if (state.rng.nextInt(tieCount) == 0) bestMove = m
                }
            }
        }
        return bestMove
    }

    /** Private sink for stall-swap and ejection-chain candidates — kept out of
     *  [LocalSearchState.moveSink] so coordinated escapes are excluded from the noise draw by
     *  construction (see [pickMove]). */
    private val swapSink: MoveSink = MoveSink()

    /** Score a candidate move. **Feasibility-first**: the objective component is gated behind
     *  `state.cost == 0`. At infeasibility we ignore the objective entirely so the search isn't
     *  pulled away from constraint satisfaction by a competing gradient — pure weighted-violation
     *  delta wins. At feasibility the objective is the only signal that distinguishes the
     *  equally-cost-0 candidates, so it fully drives. */
    private fun score(scoring: MoveScoring, state: LocalSearchState, move: Move): Double = when (scoring) {
        // Shaped break already folds the objective; the other bases gate it behind feasibility.
        MoveScoring.Break -> state.shapedBreakScore(move)

        MoveScoring.Weighted -> state.weightedNetDelta(move) + feasibleObjectiveDelta(state, move)

        MoveScoring.Raw -> state.netDelta(move).toDouble() + feasibleObjectiveDelta(state, move)
    }

    private fun feasibleObjectiveDelta(state: LocalSearchState, move: Move): Double =
        if (state.cost == 0L) state.shapedObjectiveDelta(move) else 0.0

    /** Violated-factor repair source backing [sampleFromViolated]. The duplicated draw loop lives in
     *  [ViolatedRepairs] (epic #710); this schedule supplies its `violatedSampleCount`. */
    private val violatedRepairs = ViolatedRepairs(violatedSampleCount)

    /** Plateau-escape frontier source backing [sampleFrontier] — see [Frontier]. */
    private val frontier = Frontier(violatedSampleCount, frontierMoveCap)

    private fun sampleFromViolated(state: LocalSearchState, sink: MoveSink) {
        violatedRepairs.generate(state, sink)
    }

    /** **Frontier moves** for plateau escape: when the search is trapped, the violated-only repair
     *  pool can't get out — every repair of a violated factor breaks a *satisfied neighbour*, and the
     *  moves that would first re-arrange those neighbours are never generated (satisfied factors are
     *  sampled only at feasibility). [Frontier] injects bounded ±1 / bool-flip moves on the variables
     *  of factors that *neighbour* a violated factor (share a variable), giving the search — together
     *  with the raised stall noise — moves to step through the basin wall. Capped at [frontierMoveCap]
     *  per call. */
    private fun sampleFrontier(state: LocalSearchState, sink: MoveSink) {
        frontier.generate(state, sink)
    }

    /** Plateau-buster swap source backing [sampleStallSwaps] — see [StallSwaps]. */
    private val stallSwaps = StallSwaps(stallSwapCap)

    /** Ejection-chain source backing [sampleStallChains] — see [EjectionChains]. */
    private val ejectionChains = EjectionChains(stallChainCap, stallChainDepth)

    /** Objective-direction seed source backing [seedObjectiveMoves] — see [ObjectiveSeed]. */
    private val objectiveSeed = ObjectiveSeed()

    private fun sampleStallSwaps(state: LocalSearchState, sink: MoveSink) {
        stallSwaps.generate(state, sink)
    }

    private fun sampleStallChains(state: LocalSearchState, sink: MoveSink) {
        ejectionChains.generate(state, sink)
    }

    /** Build one targeted kick (see [stallKickAfter]): a **random walk** over the variable–factor
     *  occurrence graph starting at a random violated factor, randomizing up to [stallKickVars]
     *  distinct variables along the way. A walk rather than a cone sample: coupled structures
     *  (successor chains, channeling rings) stretch the stuck region many hops from the violated
     *  factor while wide global constraints make even the 2-hop cone span most of the problem — a cone
     *  sample dilutes the kick over uncoupled variables, a walk follows the coupling and can reach
     *  e.g. the head of a parasitic successor chain whose dangling tail is the only violation (the
     *  measured prize-collecting orbit shape). Returns null when nothing eligible. */
    private fun buildStallKick(state: LocalSearchState): Move? {
        kickSink.clear()
        stallKick.generate(state, kickSink)
        return kickSink.list.firstOrNull()
    }

    /** Targeted-kick source backing [buildStallKick] — see [StallKick]. */
    private val stallKick = StallKick(stallKickVars)

    /** Read-back sink for [buildStallKick]: [StallKick] emits its one flattened perturbation here and
     *  the schedule applies it directly (it is the certified-stuck escalation, not scored). */
    private val kickSink: MoveSink = MoveSink()

    /** Satisfied-factor structured-move source backing [sampleFromSatisfied] — the random-sampling
     *  variant of [SatisfiedStructured], which the minimize engine also uses (enumerate-all). */
    private val satisfiedStructured = SatisfiedStructured.sampled(satisfiedSampleCount)

    private fun sampleFromSatisfied(state: LocalSearchState, sink: MoveSink) {
        // At infeasibility the structured-move source contributes nothing useful — its moves only
        // matter when the engine is already at cost==0 and looking for objective-improving steps.
        // Sampling here just dilutes the candidate pool while the search should be focused on closing
        // violations. The source itself is Phase.Feasible; this gate is the (still per-schedule)
        // enforcement of it.
        if (state.cost > 0) return
        satisfiedStructured.generate(state, sink)
    }

    /** Implicit-solving source backing [sampleElectedStructured] — the [SatisfiedStructured.elected]
     *  variant of the single structured generator (epic #710). */
    private val electedStructured = SatisfiedStructured.elected(implicitStructuredCap)

    /** Implicit-solving source (see [implicitStructuredCap]): during infeasibility, draw
     *  feasibility-preserving structured moves from elected structural globals that are *currently
     *  satisfied*. Unlike [sampleFromSatisfied] (which scans random factors and is gated off at
     *  infeasibility) this iterates only the small elected set, so it stays cheap while the search is
     *  still closing violations. The moves preserve the elected global, so they only improve the
     *  score when they help a coupled constraint. */
    private fun sampleElectedStructured(state: LocalSearchState, sink: MoveSink) {
        if (implicitStructuredCap == 0) return
        electedStructured.generate(state, sink)
    }

    /** Seed single-variable moves directly on the objective's nonzero-weight vars. Without this, a
     *  fully-satisfied state with no factor proposing structured moves has zero candidates and the
     *  pick returns null — engine restarts spuriously. Skipped at infeasibility for the same reason as
     *  [sampleFromSatisfied]: the objective gradient doesn't matter when we're still chasing
     *  violations, and the engine has proposeRepairMoves to cover that phase. */
    private fun seedObjectiveMoves(state: LocalSearchState, sink: MoveSink) {
        if (state.cost > 0) return
        objectiveSeed.generate(state, sink)
    }
}
