package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.Move
import com.eignex.klause.solver.localsearch.LocalSearchState
import com.eignex.klause.solver.localsearch.MoveSink
import com.eignex.klause.solver.localsearch.proposeRepairChains
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.IntHashSet

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
     *  compounds injected per stalled [pickMove] (see
     *  [LocalSearchState.proposeRepairChains]). Where [stallSwapCap] hard-codes the one
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
     *  is gated to `state.cost > 0`. */
    val implicitStructuredCap: Int = 4,
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
    private fun score(state: LocalSearchState, move: Move): Double {
        val violationDelta = when (scoring) {
            MoveScoring.Weighted -> state.weightedNetDelta(move)
            MoveScoring.Raw -> state.netDelta(move).toDouble()
        }
        val objDelta = if (state.cost == 0L) state.shapedObjectiveDelta(move) else 0.0
        return violationDelta + objDelta
    }

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

    private fun sampleFromViolated(state: LocalSearchState, sink: MoveSink) {
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
    private fun sampleFrontier(state: LocalSearchState, sink: MoveSink) {
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
    private fun addNeighbourMoves(state: LocalSearchState, sink: MoveSink, nf: Int, budget: Int): Int {
        var b = budget
        val nfac = state.factors[nf]
        for (u in nfac.intVars) {
            if (b <= 0) return b
            val cur = state.assignment.intValue(u)
            val d = state.problem.intDomains[u]
            if (cur < d.max) {
                sink.addChannelingIntSet(state, u, cur + 1)
                b--
            }
            if (b <= 0) return b
            if (cur > d.min) {
                sink.addChannelingIntSet(state, u, cur - 1)
                b--
            }
        }
        for (u in nfac.boolVars) {
            if (b <= 0) return b
            sink.addBoolFlip(u)
            b--
        }
        return b
    }

    /** Stall-gated int-pair swap proposals (see [stallSwapCap]). Randomized draws: pick a
     *  violated factor, take one of its int vars `u`, and pair it with either another var of
     *  the same factor or a var of a frontier (variable-sharing) factor. A legal swap needs
     *  differing values and cross-compatible domains; the [MoveSink] handles frozen-var
     *  filtering and dedup. Scored like any candidate — a swap that repairs the violated
     *  factor while preserving its satisfied neighbours scores strictly negative, which is
     *  exactly the signal the single-set pool can't produce on these plateaus. */
    private fun sampleStallSwaps(state: LocalSearchState, sink: MoveSink) {
        if (stallSwapCap <= 0 || state.violated.isEmpty()) return
        val rng = state.rng
        val problem = state.problem
        var budget = stallSwapCap
        // Randomized rejection sampling; most draws on bool-only or single-var factors miss,
        // so allow a few attempts per requested swap before giving up.
        var attempts = stallSwapCap * ATTEMPTS_PER_SWAP
        while (budget > 0 && attempts-- > 0) {
            val fid = state.violated.random(rng)
            val vars = state.factors[fid].intVars
            if (vars.isEmpty()) continue
            val u = vars[rng.nextInt(vars.size)]
            val w = if (vars.size >= 2 && rng.nextBoolean()) {
                vars[rng.nextInt(vars.size)]
            } else {
                val occ = problem.intOccurrences[u]
                if (occ.isEmpty()) continue
                val nvars = state.factors[occ[rng.nextInt(occ.size)]].intVars
                if (nvars.isEmpty()) continue
                nvars[rng.nextInt(nvars.size)]
            }
            if (w == u) continue
            // The private swap sink bypasses the state sink's assumption filtering — check
            // frozen vars explicitly (mirrors the engine's post-feasibility pairSwapStep).
            if (state.assumptions.isFrozenInt(u) || state.assumptions.isFrozenInt(w)) continue
            val du = problem.intDomains[u]
            val dw = problem.intDomains[w]
            // Same-shaped domains only: swaps target permutation/assignment structure
            // (course→period style vars sharing one value range). Cross-domain swaps (e.g. a
            // decision var against a derived load/count var) are semantically meaningless and
            // measured to thrash the plateau rather than walk it.
            if (du.min != dw.min || du.max != dw.max) continue
            val vu = state.assignment.intValue(u)
            val vw = state.assignment.intValue(w)
            if (vu == vw) continue
            if (vw !in du || vu !in dw) continue
            sink.addCompound(listOf(Move.IntSet(u, vw), Move.IntSet(w, vu)))
            budget--
        }
    }

    /** Stall-gated ejection-chain proposals (see [stallChainCap]): grow up to the cap of
     *  directed repair chains from random violated seed factors, each chain entering the
     *  score-only race as one atomic compound. Construction is delegated to
     *  [LocalSearchState.proposeRepairChains]; this just spends the per-pick budget. */
    private fun sampleStallChains(state: LocalSearchState, sink: MoveSink) {
        if (stallChainCap <= 0 || state.violated.isEmpty()) return
        var budget = stallChainCap
        repeat(minOf(stallChainCap, state.violated.size)) {
            if (budget <= 0) return
            val fid = state.violated.random(state.rng)
            budget -= state.proposeRepairChains(
                seedFactor = fid,
                maxDepth = stallChainDepth,
                firstMoveCap = CHAIN_FIRST_MOVES,
                sink = sink,
            )
        }
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
        if (state.violated.isEmpty()) return null
        val problem = state.problem
        var factor = state.factors[state.violated.random(state.rng)]
        // Reuse the strategy-private sink so frozen/defined filtering applies; channeling
        // keeps indicator bools consistent with kicked int values.
        kickSink.clear()
        kickSink.setAssumptions(state.assumptions)
        kickSink.setInvariants(state.invariants)
        var budget = stallKickVars
        var attempts = stallKickVars * ATTEMPTS_PER_SWAP
        while (budget > 0 && attempts-- > 0) {
            // Step 1: a random variable of the current factor.
            val nInts = factor.intVars.size
            val nBools = factor.boolVars.size
            if (nInts + nBools == 0) break
            val pick = state.rng.nextInt(nInts + nBools)
            val occ: IntArray
            if (pick < nInts) {
                val v = factor.intVars[pick]
                val d = problem.intDomains[v]
                val span = (d.max.toLong() - d.min.toLong()).toInt()
                if (span > 0) {
                    val nv = d.min + state.rng.nextInt(span + 1)
                    if (nv != state.assignment.intValue(v)) {
                        kickSink.addChannelingIntSet(state, v, nv)
                        budget--
                    }
                }
                occ = problem.intOccurrences[v]
            } else {
                val v = factor.boolVars[pick - nInts]
                kickSink.addBoolFlip(v)
                budget--
                occ = problem.boolOccurrences[v]
            }
            // Step 2: hop to a random factor sharing that variable and continue the walk.
            if (occ.isEmpty()) break
            factor = state.factors[occ[state.rng.nextInt(occ.size)]]
        }
        // Flatten everything queued into one atomic perturbation, first-write-wins per slot.
        val parts = ArrayList<Move>()
        val seenSlots = IntHashSet()
        fun addPart(p: Move) {
            val slot = when (p) {
                is Move.BoolFlip -> p.varId
                is Move.IntSet -> state.problem.numBoolVars + p.varId
                is Move.Compound -> return
            }
            if (seenSlots.add(slot)) parts.add(p)
        }
        for (m in kickSink.list) {
            when (m) {
                is Move.Compound -> for (p in m.parts) addPart(p)
                else -> addPart(m)
            }
        }
        return when (parts.size) {
            0 -> null
            1 -> parts[0]
            else -> Move.Compound(parts)
        }
    }

    /** Private sink for [buildStallKick] proposals. */
    private val kickSink: MoveSink = MoveSink()

    private fun sampleFromSatisfied(state: LocalSearchState, sink: MoveSink) {
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

    /** Implicit-solving source (see [implicitStructuredCap]): during infeasibility, draw
     *  feasibility-preserving structured moves from elected structural globals that are
     *  *currently satisfied*. Unlike [sampleFromSatisfied] (which scans random factors and is
     *  gated off at infeasibility) this iterates only the small elected set, so it stays cheap
     *  while the search is still closing violations. The moves preserve the elected global, so
     *  they only improve the score when they help a coupled constraint. */
    private fun sampleElectedStructured(state: LocalSearchState, sink: MoveSink) {
        if (implicitStructuredCap == 0) return
        val elected = state.electedImplicit
        if (elected.isEmpty()) return
        repeat(minOf(implicitStructuredCap, elected.size)) {
            val fid = elected[state.rng.nextInt(elected.size)]
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
    private fun seedObjectiveMoves(state: LocalSearchState, sink: MoveSink) {
        if (state.cost > 0) return
        val obj = state.objective ?: return
        when (obj) {
            is LinearObjective -> {
                for (v in obj.boolWeights.indices) {
                    if (obj.boolWeights[v] == 0L) continue
                    sink.addBoolFlip(v)
                }
                for (v in obj.intCoefficients.indices) {
                    if (obj.intCoefficients[v] == 0L) continue
                    val cur = state.assignment.intValue(v)
                    val d = state.problem.intDomains[v]
                    // Step in the direction the coefficient says reduces the objective.
                    // Channeling-aware so int-move + indicator updates stay atomic.
                    if (obj.intCoefficients[v] > 0 && cur > d.min) sink.addChannelingIntSet(state, v, cur - 1)
                    if (obj.intCoefficients[v] < 0 && cur < d.max) sink.addChannelingIntSet(state, v, cur + 1)
                }
            }

            is FunctionalObjective -> {
                // Decomposed objective: its gradient lives in deltaIfApplied, not in per-var
                // coefficients, so we can't pick a direction a priori. Seed *geometric* steps
                // (±1, ±2, ±4, …, plus the domain endpoints) on each decision (leaf) variable
                // and let the move scoring (which folds in the functional objective delta) keep
                // the best. Pure ±1 descends a wide-domain coordinate objective far too slowly;
                // geometric steps let the search jump while still refining at unit resolution.
                for (v in obj.leafVars) {
                    val cur = state.assignment.intValue(v)
                    val d = state.problem.intDomains[v]
                    var step = 1
                    while (step <= OBJ_SEED_MAX_STEP) {
                        val up = cur + step
                        val down = cur - step
                        if (up <= d.max) sink.addChannelingIntSet(state, v, up)
                        if (down >= d.min) sink.addChannelingIntSet(state, v, down)
                        if (up > d.max && down < d.min) break
                        step = step shl 1
                    }
                    if (cur != d.min) sink.addChannelingIntSet(state, v, d.min)
                    if (cur != d.max) sink.addChannelingIntSet(state, v, d.max)
                }
            }

            else -> { /* no per-var direction without inspecting the objective shape */ }
        }
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
        /** Largest geometric step seeded per leaf var during functional-objective descent. */
        private const val OBJ_SEED_MAX_STEP = 4096

        /** Rejection-sampling attempts allowed per requested stall swap (see [sampleStallSwaps]). */
        private const val ATTEMPTS_PER_SWAP = 4

        /** First-move branch width per ejection-chain seed factor (see [sampleStallChains]). */
        private const val CHAIN_FIRST_MOVES = 4

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
