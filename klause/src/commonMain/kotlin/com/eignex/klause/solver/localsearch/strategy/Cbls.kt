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

/**
 * Constraint-Based Local Search strategy. Unlike SAT-family strategies ([ProbSat],
 * [WalkSat]) that route picks through a randomly-chosen violated factor, CBLS
 * scores moves against a *global* weighted-violation gradient:
 *
 *   `score(move) = Σ factorWeights`f` · Δviolated`f` + shapingLambda · Δobjective`
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
 *     [smoothFactor] of the way back toward its seeded baseline (the per-factor initial weights
 *     scaled by [baseWeight]). Smoothing is a forgetting mechanism that counteracts the otherwise-
 *     monotone weight growth, so factors that are no longer hard decay back and the gradient doesn't
 *     ossify on long plateau-heavy runs — and, targeting the seed rather than a flat constant, it
 *     restores the proactive per-class / implied landscape instead of flattening it. Disabled by
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
 * generalises across CP shapes. Tune `shapingLambda` upward on objective-heavy problems
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
    /** Scale on the per-factor smoothing target: smoothing pulls each weight toward
     *  `baseWeight · `[LocalSearchState.baseFactorWeights]`[f]`. `1.0` (default) targets the seed
     *  exactly; only consulted when [smoothProb] > 0. */
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
    /** **Plateau-buster** (opt-in, `0` = off = exact default behavior): cap on stall-gated
     *  int-pair swap moves injected per stalled [pickMove]. The feasibility fight's repair
     *  pool is otherwise single-set moves (plus ±1 frontier steps), and on assignment-shaped
     *  instances (bacp/curriculum-style course→period with load bounds) every single int-set
     *  breaks an equal-coefficient sum — the reification plateau where the best single repair
     *  is Δ ≥ 0. A swap (`u ← value(w)`, `w ← value(u)`) between same-domain vars fixes
     *  ordering/channel violations while *preserving* those sums — the move class the
     *  engine's `LocalSearchSolver` pair-swap only offers after feasibility.
     *
     *  Enabling this also switches the *stalled* noise draw to primitive moves only — the
     *  two are a package: measured on bacp (diag, 3 seeds × 3M flips), baseline plateaus at
     *  cost 7, swaps alone thrash to 67 (hot stall noise randomly fires destructive 2-var
     *  perturbations), the noise change alone drifts to 32, and the pair solves 3/3 (and
     *  curriculum 3/3, mqueens at 10 s). Kept off by default because the same hot-noise
     *  restriction starves instances whose diversification relies on randomly-taken factor
     *  compounds (spot5/tpp/java-auto-gen lose feasibility): a corpus sweep measured net
     *  −2 feasible with it on everywhere. Use via the portfolio's plateau worker or set
     *  explicitly on permutation/assignment-shaped problems. */
    val stallSwapCap: Int = 0,
    /** **Ejection chains** (opt-in, `0` = off): cap on stall-gated directed repair-chain
     *  compounds injected per stalled [pickMove] (see [EjectionChains]). Where [stallSwapCap]
     *  hard-codes the one
     *  coordinated shape assignment plateaus need (a same-domain pair swap), chains *derive*
     *  the coordinated move from the break structure: apply a violated factor's repair,
     *  find the factor it newly regressed, append that factor's best eligible repair, and
     *  repeat to [stallChainDepth] — emitting the walk's best ≥2-part prefix as one atomic
     *  compound. This is the move class successor/path encodings
     *  (pos/next models à la prize-collecting) are missing: relocating a node into the tour
     *  requires re-linking predecessor, position and tail in one move, which no bounded set
     *  of single-var repairs (and no same-domain swap) expresses.
     *
     *  Enters the candidate race the same way the stall swaps do — **score only, never the
     *  noise draw**, with stalled noise restricted to primitive moves while active. Those
     *  two properties are the measured package (#139 ablation): coordinated moves taken by
     *  dice thrash plateaus (bacp 8 → 104 under random coupling chains), the same shapes
     *  taken by score are the escape mechanism. */
    val stallChainCap: Int = 0,
    /** Maximum repair steps per ejection chain (only consulted when [stallChainCap] > 0). */
    val stallChainDepth: Int = 4,
    /** **Targeted kick** (opt-in, `0` = off): steps without a strict cost drop before a
     *  bounded LNS-style perturbation fires — randomize up to [stallKickVars] variables in
     *  the 2-hop neighbourhood of a random violated factor and let the descent re-converge.
     *  The escalation tier above [stallChainCap]: score-gated chains escape plateaus whose
     *  exit is expressible as a bounded directed walk, but the repair-graph probe on
     *  prize-collecting proved some cost-1 orbits are *closed* — every exit passes through
     *  states several violations uphill, beyond any score-gated move's reach. A kick is a
     *  dice move, but unlike the measured-negative hot-noise compound picks (#139: bacp
     *  8 → 104) it is (a) rare — fires once per [stallKickAfter]-step certified-stuck
     *  window, not per stalled pick, and (b) local — confined to the violated factor's
     *  2-hop variable cone, the region the search has proven it cannot repair in place.
     *  Should be ≫ [frontierAfterStall] so chains get their chance first. */
    val stallKickAfter: Int = 0,
    /** Variables randomized per targeted kick (only consulted when [stallKickAfter] > 0). */
    val stallKickVars: Int = 8,
    val tabu: TabuFilter = TabuFilter.Disabled,
    /** Move-scoring basis. [MoveScoring.Weighted] (default) scores by the per-factor
     *  [LocalSearchState.factorWeights] gradient — the CBLS signal. [MoveScoring.Raw] scores by
     *  the plain (unweighted) violation-count delta — the classical VND signal. The merged
     *  CBLS×VND configuration keeps [MoveScoring.Weighted] so the variable-neighborhood ladder
     *  is steered by the learned constraint weights. */
    val scoring: MoveScoring = MoveScoring.Weighted,
    /** Variable-neighborhood-descent ladder depth. `1` (default) = the flat CBLS candidate
     *  pool (existing behavior, unchanged). `>1` engages an escalating k-level neighborhood:
     *  level `k` proposes coordinated k-deep [couplingChain] moves, only escalating to `k+1`
     *  when level `k` yields no accepted move, and resetting to level 1 on a strict cost drop.
     *  This is the VND half of the merge — the structured large-move neighborhood CBLS's flat
     *  pool lacks — while move *scoring* and *weight learning* stay CBLS's. */
    val maxNeighborhood: Int = 1,
    /** Candidate moves generated per ladder level. Only consulted when [maxNeighborhood] > 1. */
    val candidatesPerLevel: Int = 4,
    /** Skewed-VNS acceptance (Hansen et al. 2010). When non-zero, a move is accepted as
     *  "improving" if `score + skewAlpha · moveSize < 0`, letting the descent take a
     *  slightly-worsening move whose spatial reach is small — the classical mechanism for
     *  crossing plateau lakes. `0.0` (default) = strict descent on the scored delta. */
    val skewAlpha: Double = 0.0,
    /** **Implicit-solving neighbourhoods** (`0` = off): cap on elected structural globals
     *  (see [LocalSearchState.electedImplicit]) sampled per *infeasible* [pickMove] for their
     *  feasibility-preserving structured moves. Those moves never break the global itself, so
     *  they enter the noise-eligible pool and are scored by the weighted-violation gradient —
     *  winning only when the structure-preserving relocation clears a clash in a coupled
     *  constraint (e.g. swapping two cells of one all-different to fix the column it shares).
     *  At feasibility [sampleFromSatisfied] already owns the structured pool, so this source
     *  is gated to `state.cost > 0`. `0` (default) = off, so default convergence is unchanged;
     *  enabled by the portfolio on permutation/assignment-shaped models. */
    val implicitStructuredCap: Int = 0,
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
        require(stallSwapCap >= 0) { "stallSwapCap ≥ 0, got $stallSwapCap" }
        require(stallChainCap >= 0) { "stallChainCap ≥ 0, got $stallChainCap" }
        require(stallChainDepth >= 2) { "stallChainDepth ≥ 2, got $stallChainDepth" }
        require(stallKickAfter >= 0) { "stallKickAfter ≥ 0, got $stallKickAfter" }
        require(stallKickVars >= 1) { "stallKickVars ≥ 1, got $stallKickVars" }
        require(maxNeighborhood >= 1) { "maxNeighborhood ≥ 1, got $maxNeighborhood" }
        require(candidatesPerLevel >= 1) { "candidatesPerLevel ≥ 1, got $candidatesPerLevel" }
        require(skewAlpha >= 0.0) { "skewAlpha ≥ 0, got $skewAlpha" }
        require(implicitStructuredCap >= 0) { "implicitStructuredCap ≥ 0, got $implicitStructuredCap" }
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
                lastImprovingStep = state.step
            }
            lastSeenStep = state.step
        }

        // VND ladder mode (the CBLS×VND merge): when a neighborhood ladder is configured,
        // drive the *infeasible* fight through escalating coordinated neighborhoods — scored
        // by [scoring] (Weighted keeps the CBLS gradient) and accepted via [accepts] (skewed
        // when [skewAlpha] > 0). At feasibility we fall through to the flat objective-descent
        // pool below, which owns the satisfied/objective candidate sources. Defaults
        // ([maxNeighborhood] == 1) skip this entirely, leaving the flat CBLS path unchanged.
        if (maxNeighborhood > 1 && state.cost > 0L) return pickMoveLadder(state)

        // Targeted kick escalation (see [stallKickAfter]): the search has been certified
        // stuck for a window long enough that chains/swaps/noise have all had their chance —
        // perturb the violated factor's local cone and restart the descent clock.
        if (stallKickAfter > 0 && state.cost > 0 && state.step - lastDropStep >= stallKickAfter) {
            val kick = buildStallKick(state)
            if (kick != null) {
                lastDropStep = state.step // fresh descent window after the perturbation
                return kick
            }
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
        if (state.cost > 0L) sampleElectedStructured(state, sink)
        seedObjectiveMoves(state, sink)

        // Stall swaps and ejection chains live in a private sink: they compete on *score
        // only*, never in the noise draw. Random-picking a multi-var coordinated move is a
        // large destructive perturbation (measured on bacp: min 7 → 67); score-picked, the
        // same moves are exactly the coordinated escape the reification plateaus need
        // (bacp/curriculum solve). All other candidates — including factor-proposed
        // compounds — keep the original noise-eligible behavior; restricting those too
        // regressed the instances whose diversification relied on them (rcpsp-wet, spot5,
        // tpp).
        swapSink.clear()
        if (stalled) {
            sampleStallSwaps(state, swapSink)
            sampleStallChains(state, swapSink)
        }

        var raw = sink.list
        if (raw.isEmpty() && swapSink.list.isEmpty() && !stalled) {
            // Starvation fallback: under per-move invariants a violated factor whose repair
            // proposals all target *defined* vars contributes nothing — on reified-heavy
            // models (every indicator bool defined) whole picks come up empty, the engine
            // restarts, and because null picks never advance `state.step` the stall
            // machinery that would broaden the pool never engages (measured on
            // prize-collecting: restart churn at cost ≈166 under invariants). Broaden
            // immediately instead: frontier moves reach the searched neighbour vars, and
            // chains (when enabled) walk the break structure from them.
            sampleFrontier(state, sink)
            sampleStallChains(state, swapSink)
            raw = sink.list
        }
        val swaps = swapSink.list
        if (raw.isEmpty() && swaps.isEmpty()) return null
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
        if (moves.isNotEmpty() && state.rng.nextDouble() < effectiveNoise) {
            // Plateau-buster half 2 (see [stallSwapCap]): while stalled, draw noise from
            // primitive moves only. Stalled noise runs hot (stallNoise = 0.4) and at that
            // rate random compound picks are a large destructive perturbation — measured on
            // bacp they thrash the plateau — while the same compounds score-picked are the
            // escape mechanism. Gated with the swaps because alone it starves landscapes
            // whose diversification relies on randomly-taken factor compounds.
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
                val s = score(state, m)
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
     *  [LocalSearchState.moveSink] so coordinated escapes are excluded from the noise draw
     *  by construction (see [pickMove]). */
    private val swapSink: MoveSink = MoveSink()

    /** Score a candidate move. **Feasibility-first**: the objective component is gated
     *  behind `state.cost == 0`. At infeasibility we ignore the objective entirely so the
     *  search isn't pulled away from constraint satisfaction by a competing gradient —
     *  pure weighted-violation delta wins. At feasibility the objective is the only
     *  signal that distinguishes the equally-cost-0 candidates, so it fully drives. */
    private fun score(state: LocalSearchState, move: Move): Double = when (scoring) {
        // Shaped break already folds the objective; the other bases gate it behind feasibility.
        MoveScoring.Break -> state.shapedBreakScore(move)

        MoveScoring.Weighted -> state.weightedNetDelta(move) + feasibleObjectiveDelta(state, move)

        MoveScoring.Raw -> state.netDelta(move).toDouble() + feasibleObjectiveDelta(state, move)
    }

    private fun feasibleObjectiveDelta(state: LocalSearchState, move: Move): Double =
        if (state.cost == 0L) state.shapedObjectiveDelta(move) else 0.0

    /** Move "size" for skewed-VNS acceptance: 1 for primitives, part-count for compounds. */
    private fun moveSize(move: Move): Int = when (move) {
        is Move.BoolFlip, is Move.IntSet -> 1
        is Move.Compound -> move.parts.size
    }

    /** Skewed-VNS acceptance test: strict descent when [skewAlpha] is 0, otherwise admits a
     *  slightly-worsening move whose spatial reach (size) is small. */
    private fun accepts(state: LocalSearchState, move: Move): Boolean {
        val s = score(state, move)
        return if (skewAlpha == 0.0) s < 0.0 else s + skewAlpha * moveSize(move) < 0.0
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
     *  [smoothFactor] of the way back toward its seeded baseline ([LocalSearchState.baseFactorWeights]
     *  scaled by [baseWeight]). [bumpViolatedWeights] only ever grows weights, so without a
     *  counter-pressure the gradient ossifies on long runs; smoothing lets weight on factors that are
     *  no longer hard decay back. Targeting the per-factor seed rather than a flat constant means the
     *  decay restores the proactive per-class / implied landscape from
     *  [LocalSearchState.factorWeights] instead of flattening it. Called with probability [smoothProb]
     *  right after a stall bump. */
    private fun smoothAllWeights(state: LocalSearchState) {
        val w = state.factorWeights
        val base = state.baseFactorWeights
        val keep = 1.0 - smoothFactor
        for (i in w.indices) w[i] = keep * w[i] + smoothFactor * baseWeight * base[i]
    }

    /** Violated-factor repair source backing [sampleFromViolated]. The duplicated draw loop now
     *  lives in [ViolatedRepairs] (epic #710); this strategy supplies its `violatedSampleCount`. */
    private val violatedRepairs = ViolatedRepairs(violatedSampleCount)

    /** Plateau-escape frontier source backing [sampleFrontier] — see [Frontier]. */
    private val frontier = Frontier(violatedSampleCount, frontierMoveCap)

    private fun sampleFromViolated(state: LocalSearchState, sink: MoveSink) {
        violatedRepairs.generate(state, sink)
    }

    /** **Frontier moves** for plateau escape: when the search is trapped, the violated-only
     *  repair pool can't get out — every repair of a violated factor breaks a *satisfied
     *  neighbour*, and the moves that would first re-arrange those neighbours are never
     *  generated (satisfied factors are sampled only at feasibility). [Frontier] injects bounded
     *  ±1 / bool-flip moves on the variables of factors that *neighbour* a violated factor
     *  (share a variable), giving the search — together with the raised stall noise — moves
     *  to step through the basin wall. Capped at [frontierMoveCap] per call. */
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

    /** Build one targeted kick (see [stallKickAfter]): a **random walk** over the
     *  variable–factor occurrence graph starting at a random violated factor, randomizing
     *  up to [stallKickVars] distinct variables along the way. A walk rather than a cone
     *  sample: coupled structures (successor chains, channeling rings) stretch the stuck
     *  region many hops from the violated factor while wide global constraints make even
     *  the 2-hop cone span most of the problem — a cone sample dilutes the kick over
     *  uncoupled variables, a walk follows the coupling and can reach e.g. the head of a
     *  parasitic successor chain whose dangling tail is the only violation (the measured
     *  prize-collecting orbit shape). Returns null when nothing eligible. */
    private fun buildStallKick(state: LocalSearchState): Move? {
        kickSink.clear()
        stallKick.generate(state, kickSink)
        return kickSink.list.firstOrNull()
    }

    /** Targeted-kick source backing [buildStallKick] — see [StallKick]. */
    private val stallKick = StallKick(stallKickVars)

    /** Read-back sink for [buildStallKick]: [StallKick] emits its one flattened perturbation here
     *  and the strategy applies it directly (it is the certified-stuck escalation, not scored). */
    private val kickSink: MoveSink = MoveSink()

    /** Satisfied-factor structured-move source backing [sampleFromSatisfied] — the random-sampling
     *  variant of [SatisfiedStructured], which the minimize engine also uses (enumerate-all). */
    private val satisfiedStructured = SatisfiedStructured.sampled(satisfiedSampleCount)

    private fun sampleFromSatisfied(state: LocalSearchState, sink: MoveSink) {
        // At infeasibility the structured-move source contributes nothing useful — its
        // moves only matter when the engine is already at cost==0 and looking for
        // objective-improving steps. Sampling here just dilutes the candidate pool while
        // the search should be focused on closing violations. The source itself is
        // Phase.Feasible; this gate is the (still per-strategy) enforcement of it.
        if (state.cost > 0) return
        satisfiedStructured.generate(state, sink)
    }

    /** Implicit-solving source backing [sampleElectedStructured] — the [SatisfiedStructured.elected]
     *  variant of the single structured generator (epic #710). */
    private val electedStructured = SatisfiedStructured.elected(implicitStructuredCap)

    /** Implicit-solving source (see [implicitStructuredCap]): during infeasibility, draw
     *  feasibility-preserving structured moves from elected structural globals that are
     *  *currently satisfied*. Unlike [sampleFromSatisfied] (which scans random factors and is
     *  gated off at infeasibility) this iterates only the small elected set, so it stays cheap
     *  while the search is still closing violations. The moves preserve the elected global, so
     *  they only improve the score when they help a coupled constraint. */
    private fun sampleElectedStructured(state: LocalSearchState, sink: MoveSink) {
        if (implicitStructuredCap == 0) return
        electedStructured.generate(state, sink)
    }

    /** Seed single-variable moves directly on the objective's nonzero-weight vars. Without
     *  this, a fully-satisfied state with no factor proposing structured moves has zero
     *  candidates and pickMove returns null — engine restarts spuriously. Skipped at
     *  infeasibility for the same reason as [sampleFromSatisfied]: the objective gradient
     *  doesn't matter when we're still chasing violations, and the engine has
     *  proposeRepairMoves to cover that phase. */
    private fun seedObjectiveMoves(state: LocalSearchState, sink: MoveSink) {
        if (state.cost > 0) return
        objectiveSeed.generate(state, sink)
    }

    /**
     * Variable-neighborhood-descent pick over the infeasible landscape (the VND half of the
     * merge). Escalates level `k = 1 … maxNeighborhood`: level `k` generates [candidatesPerLevel]
     * coordinated k-deep moves (via [generateLevel]); the first level with an accepted move
     * (per [accepts], so skewed when [skewAlpha] > 0) returns its best-scored move, resetting
     * the ladder for next call. When no level yields an accepted move, walk the plateau from
     * the level-1 pool's least-bad move so the search keeps progress instead of forcing a
     * restart. Stall (no cost drop for [frontierAfterStall]) raises the diversification noise
     * to [stallNoise], exactly as the flat path does.
     */
    private fun pickMoveLadder(state: LocalSearchState): Move? {
        if (state.violated.isEmpty()) return null
        val stalled = frontierAfterStall > 0 && state.step - lastDropStep >= frontierAfterStall
        val effectiveNoise = if (stalled) stallNoise else noiseProbability
        var plateauPool: List<Move>? = null
        for (k in 1..maxNeighborhood) {
            val sink = state.moveSink
            sink.clear()
            generateLevel(state, k, sink)
            val raw = sink.list
            if (raw.isEmpty()) continue
            val filtered = tabu.filter(state, raw)
            // While stalled, never let tabu starve the pool into a restart — fall back to the
            // unfiltered candidates so the search keeps walking the plateau (mirrors flat path).
            val moves = if (filtered.isEmpty()) {
                if (stalled) raw else continue
            } else {
                filtered
            }
            if (plateauPool == null) plateauPool = moves.toList()
            if (state.rng.nextDouble() < effectiveNoise) return moves[state.rng.nextInt(moves.size)]
            var best: Move? = null
            var bestScore = Double.POSITIVE_INFINITY
            for (m in moves) {
                if (!accepts(state, m)) continue
                val s = score(state, m)
                if (s < bestScore) {
                    bestScore = s
                    best = m
                }
            }
            if (best != null) return best
        }
        // No accepted (improving / skew-improving) move at any level: walk the plateau.
        val pool = plateauPool ?: return null
        var best = pool[0]
        var bestScore = score(state, best)
        for (i in 1 until pool.size) {
            val s = score(state, pool[i])
            if (s < bestScore) {
                bestScore = s
                best = pool[i]
            }
        }
        return best
    }

    /** Generate level-`k` candidates into [sink]. Level 1 is the single-factor repair pool
     *  ([sampleFromViolated]); deeper levels are coordinated k-deep [couplingChain] moves. */
    private fun generateLevel(state: LocalSearchState, k: Int, sink: MoveSink) {
        if (k == 1) {
            sampleFromViolated(state, sink)
            return
        }
        repeat(candidatesPerLevel) {
            couplingChain(state, k, sink)
        }
    }

    /**
     * Build one coordinated depth-`k` move and add it to [sink]: a primitive k-factor
     * concatenation — pick `k` random violated factors and staple one random repair from each.
     */
    private fun couplingChain(state: LocalSearchState, k: Int, sink: MoveSink) {
        // Per-factor repairs are proposed into a private scratch sink, never the accumulation
        // target [sink]. Frozen-variable filtering happens when parts are added to [sink].
        val scratch = chainScratch
        val parts = ArrayList<Move>(k)
        repeat(k) {
            if (state.violated.isEmpty()) return@repeat
            val fid = state.violated.random(state.rng)
            scratch.clear()
            state.factors[fid].proposeRepairMoves(state, fid, scratch)
            val raw = scratch.list
            if (raw.isEmpty()) return@repeat
            when (val pick = raw[state.rng.nextInt(raw.size)]) {
                is Move.BoolFlip, is Move.IntSet -> parts.add(pick)
                is Move.Compound -> parts.addAll(pick.parts)
            }
        }
        when (parts.size) {
            0 -> {}

            1 -> when (val p = parts[0]) {
                is Move.BoolFlip -> sink.addBoolFlip(p.varId)
                is Move.IntSet -> sink.addIntSet(p.varId, p.newValue)
                is Move.Compound -> sink.addCompound(p.parts)
            }

            else -> sink.addCompound(parts)
        }
    }

    /** Private scratch sink for per-factor repair proposals during [couplingChain] — kept off
     *  the shared [LocalSearchState.moveSink] which the ladder uses as its accumulation target. */
    private val chainScratch: MoveSink = MoveSink()

    /** Tuning constants and the [vnd] preset factory. */
    companion object {
        /**
         * Classical Variable-Neighbourhood-Descent as a [Cbls] preset (the unified strategy
         * subsumes the former standalone `Vnd`): [MoveScoring.Raw] move scoring, a `k`-level
         * neighbourhood ladder, skewed acceptance, and a default tabu tenure. The CBLS×VND
         * *merge* is instead the plain [Cbls] constructor with [maxNeighborhood] > 1 and the
         * default [MoveScoring.Weighted] — keeping the learned-weight gradient steering the
         * ladder rather than the raw violation count.
         */
        fun vnd(
            maxNeighborhood: Int = 3,
            candidatesPerLevel: Int = 4,
            noise: Double = 0.05,
            skewAlpha: Double = 0.0,
            tabu: TabuFilter = TabuFilter(tenure = 10),
        ): Cbls = Cbls(
            noiseProbability = noise,
            tabu = tabu,
            scoring = MoveScoring.Raw,
            maxNeighborhood = maxNeighborhood,
            candidatesPerLevel = candidatesPerLevel,
            skewAlpha = skewAlpha,
        )
    }
}
