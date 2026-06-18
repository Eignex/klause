package com.eignex.klause.solver.localsearch.strategy

/**
 * Constraint-Based Local Search, re-expressed as a [SourceDrivenStrategy] in its stall-schedule mode
 * (#721). Unlike the SAT-family strategies ([ProbSat], [WalkSat]) that route picks through a randomly
 * chosen violated factor, CBLS scores moves against a *global* weighted-violation gradient and is
 * driven by a stateful stall schedule ([CblsSchedule]) — adaptive weights, stall-gated frontier/swap/
 * chain sources, stall-aware noise, and a targeted kick — which the driver's flat recipe loop cannot
 * express. The generation is the shared catalog (epic #710); the schedule is the orchestration.
 *
 * Defaults match the yuck-style "moderate noise, gentle stall pressure" regime that generalises
 * across CP shapes. Tune `shapingLambda` upward on objective-heavy problems where the constraint
 * gradient dominates; tune [stallIncrement] up on plateau-heavy landscapes.
 */
// FunctionNaming: factory mirroring the historical strategy constructor it replaced.
// LongParameterList: the CBLS tuning surface; defaults are the tuned regime.
@Suppress("FunctionNaming", "LongParameterList")
fun Cbls(
    noiseProbability: Double = 0.05,
    /** Steps without strict improvement before weights are bumped on violated factors. */
    stallSteps: Int = 1,
    /** Per-bump weight increment for violated factors. */
    stallIncrement: Double = 1.0,
    /** Probability of applying a smoothing pass after a stall bump. `0.0` (default) disables
     *  smoothing entirely, leaving the monotone bump-only schedule. */
    smoothProb: Double = 0.0,
    /** Smoothing pull strength: each smoothed weight moves this fraction of the way toward
     *  `baseWeight` (`w ← (1 - smoothFactor)·w + smoothFactor·baseWeight`). Only consulted when
     *  [smoothProb] > 0. */
    smoothFactor: Double = 0.8,
    /** Scale on the per-factor smoothing target: smoothing pulls each weight toward
     *  `baseWeight · baseFactorWeights(f)`. `1.0` (default) targets the seed exactly; only consulted
     *  when [smoothProb] > 0. */
    baseWeight: Double = 1.0,
    /** Cap on violated factors sampled per pick for candidate generation. */
    violatedSampleCount: Int = 4,
    /** Cap on satisfied factors sampled per pick (for `proposeStructuredMoves`). */
    satisfiedSampleCount: Int = 4,
    /** Steps without a strict *cost drop* before the **plateau-escape** machinery engages (frontier
     *  moves + raised noise). `0` disables escape. */
    frontierAfterStall: Int = 40,
    /** Cap on frontier (neighbour-variable) moves injected per stalled pick. */
    frontierMoveCap: Int = 32,
    /** Effective [noiseProbability] while stalled past [frontierAfterStall] — a random-walk
     *  diversification over the broadened pool that lets strict local minima be escaped. */
    stallNoise: Double = 0.4,
    /** **Plateau-buster** (opt-in, `0` = off): cap on stall-gated int-pair swap moves injected per
     *  stalled pick — same-domain value exchanges that preserve equal-coefficient sums while fixing
     *  ordering/channel violations. Enabling also restricts the stalled noise draw to primitive moves.
     *  See the credit-campaign provenance on the portfolio's plateau worker. */
    stallSwapCap: Int = 0,
    /** **Ejection chains** (opt-in, `0` = off): cap on stall-gated directed repair-chain compounds
     *  injected per stalled pick; competes on score only, never the noise draw. */
    stallChainCap: Int = 0,
    /** Maximum repair steps per ejection chain (only consulted when [stallChainCap] > 0). */
    stallChainDepth: Int = 4,
    /** **Targeted kick** (opt-in, `0` = off): steps without a strict cost drop before a bounded
     *  LNS-style perturbation fires — randomize up to [stallKickVars] variables in the violated
     *  factor's variable cone and let the descent re-converge. Should be ≫ [frontierAfterStall]. */
    stallKickAfter: Int = 0,
    /** Variables randomized per targeted kick (only consulted when [stallKickAfter] > 0). */
    stallKickVars: Int = 8,
    tabu: TabuFilter = TabuFilter.Disabled,
    /** Move-scoring basis. [MoveScoring.Weighted] (default) scores by the per-factor learned-weight
     *  gradient — the CBLS signal. [MoveScoring.Raw] scores by the plain (unweighted) violation-count
     *  delta — the classical VND signal. */
    scoring: MoveScoring = MoveScoring.Weighted,
    /** **Implicit-solving neighbourhoods** (`0` = off): cap on elected structural globals sampled per
     *  *infeasible* pick for their feasibility-preserving structured moves. Gated to `state.cost > 0`;
     *  enabled by the portfolio on permutation/assignment-shaped models. */
    implicitStructuredCap: Int = 0,
): SourceDrivenStrategy = SourceDrivenStrategy(
    sources = emptyList(),
    scoring = scoring,
    tabu = tabu,
    cblsSchedule = CblsSchedule(
        noiseProbability = noiseProbability,
        stallSteps = stallSteps,
        stallIncrement = stallIncrement,
        smoothProb = smoothProb,
        smoothFactor = smoothFactor,
        baseWeight = baseWeight,
        violatedSampleCount = violatedSampleCount,
        satisfiedSampleCount = satisfiedSampleCount,
        frontierAfterStall = frontierAfterStall,
        frontierMoveCap = frontierMoveCap,
        stallNoise = stallNoise,
        stallSwapCap = stallSwapCap,
        stallChainCap = stallChainCap,
        stallChainDepth = stallChainDepth,
        stallKickAfter = stallKickAfter,
        stallKickVars = stallKickVars,
        implicitStructuredCap = implicitStructuredCap,
    ),
)
