package com.eignex.klause.localsearch.strategy

import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.localsearch.TabuFilter
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.CliqueSwap
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.EjectionChains
import com.eignex.klause.localsearch.movesource.FlipAndPropagate
import com.eignex.klause.localsearch.movesource.Frontier
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.localsearch.movesource.PairSwap
import com.eignex.klause.localsearch.movesource.Phase
import com.eignex.klause.localsearch.movesource.SatisfiedStructured
import com.eignex.klause.localsearch.movesource.StallKick
import com.eignex.klause.localsearch.movesource.StallSwaps
import com.eignex.klause.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.localsearch.schedule.ScheduleBundle
import com.eignex.klause.localsearch.schedule.StallSchedule
import com.eignex.klause.localsearch.schedule.WeightSchedule
import com.eignex.klause.localsearch.scoring.MoveScoring

/**
 * Constraint-Based Local Search as a [SourceDrivenStrategy] recipe. CBLS scores moves against a
 * *global* weighted-violation gradient and escapes plateaus by reweighting plus a stall-gated
 * broadening of the move pool:
 *
 *  - **sources**: violated-factor repairs always; satisfied-factor structured moves + objective-seed
 *    moves at feasibility; elected implicit globals during the infeasibility fight; and, gated on the
 *    stall signal, frontier (neighbour) moves plus the score-only stall swaps / ejection chains /
 *    flip-and-propagate compounds that the reification plateaus need.
 *  - **scoring**: [MoveScoring.Weighted] — the learned per-factor gradient (or [MoveScoring.Raw]).
 *  - **acceptance**: [AcceptanceRule.WalkSatNoise] — greedy on the gradient with a noise draw whose
 *    level the stall signal raises from [noiseProbability] to [stallNoise] while stalled.
 *  - **schedule**: the SAPS-style weight bump+smooth ([WeightSchedule.cbls]) and the [StallSchedule]
 *    stall signal (window + effective noise); the targeted [StallKick] fires as the perturbation hook.
 */
// LongParameterList: the CBLS tuning surface.
@Suppress("FunctionNaming", "LongParameterList")
fun Cbls(
    noiseProbability: Double = 0.05,
    /** Steps without strict improvement before weights are bumped on violated factors. */
    stallSteps: Int = 1,
    /** Per-bump weight increment for violated factors. */
    stallIncrement: Double = 1.0,
    /** Probability of applying a smoothing pass after a stall bump. `0.0` (default) disables smoothing. */
    smoothProb: Double = 0.0,
    /** Smoothing pull strength toward the seeded baseline. Only consulted when [smoothProb] > 0. */
    smoothFactor: Double = 0.8,
    /** Scale on the per-factor smoothing target. Only consulted when [smoothProb] > 0. */
    baseWeight: Double = 1.0,
    /** Cap on violated factors sampled per pick for candidate generation. */
    violatedSampleCount: Int = 4,
    /** Cap on satisfied factors sampled per pick (for `proposeStructuredMoves`). */
    satisfiedSampleCount: Int = 4,
    /** Steps without a strict cost drop before the plateau-escape sources + raised noise engage.
     *  `0` disables escape (the stall-gated sources never fire). */
    frontierAfterStall: Int = 40,
    /** Cap on frontier (neighbour-variable) moves injected per stalled pick. */
    frontierMoveCap: Int = 32,
    /** Effective noise while stalled — a hotter random walk to step out of the basin. */
    stallNoise: Double = 0.4,
    /** **Plateau-buster** (opt-in, `0` = off): cap on stall-gated, score-only same-domain pair-swap
     *  moves — the coordinated exchange equal-coefficient-sum (bacp-style) plateaus need. */
    stallSwapCap: Int = 0,
    /** **Ejection chains** (opt-in, `0` = off): cap on stall-gated, score-only directed repair-chain
     *  compounds per pick. */
    stallChainCap: Int = 0,
    /** Maximum repair steps per ejection chain (only consulted when [stallChainCap] > 0). */
    stallChainDepth: Int = 4,
    /** **Clique swaps** (opt-in, `0` = off): cap on stall-gated, score-only at-most-one clique-swap
     *  compounds per pick — the categorical relocation packing/assignment cliques need. */
    stallCliqueSwapCap: Int = 0,
    /** **Flip-and-propagate** (opt-in, `0` = off): cap on stall-gated, score-only implication-aware
     *  flip compounds per pick — a seed flip bundled with the literals it forces through the
     *  binary-implication graph. */
    flipPropagateCap: Int = 0,
    /** Maximum implication-following depth per flip-and-propagate move (only consulted when
     *  [flipPropagateCap] > 0). */
    flipPropagateDepth: Int = 16,
    /** **Targeted kick** (opt-in, `0` = off): steps without a strict cost drop before a bounded
     *  LNS-style [StallKick] perturbation fires. Should be ≫ [frontierAfterStall]. */
    stallKickAfter: Int = 0,
    /** Variables randomized per targeted kick (only consulted when [stallKickAfter] > 0). */
    stallKickVars: Int = 8,
    tabu: TabuFilter = TabuFilter.Disabled,
    /** Move-scoring basis: [MoveScoring.Weighted] (learned-weight gradient) or [MoveScoring.Raw]. */
    scoring: MoveScoring = MoveScoring.Weighted,
    /** **Implicit-solving neighbourhoods** (`0` = off): cap on elected structural globals sampled per
     *  *infeasible* pick for their feasibility-preserving structured moves. */
    implicitStructuredCap: Int = 0,
    /** **Objective-hot-spot pair swaps** (opt-in, `0` = off): cap on score-only [PairSwap] candidates
     *  per *feasible* pick whose first int endpoint is drawn from the objective gradient, so
     *  objective-descent swaps concentrate on variables that can move the objective. */
    pairSwapHotSpotCap: Int = 0,
    /** **Extended structured moves** (opt-in, `0` = off): cap on each factor's opt-in
     *  `proposeExtendedStructuredMoves` (circuit 2-opt, all-different 3-cycle, …) sampled per pick —
     *  drawn both at feasibility and, for elected implicit globals, during the infeasibility fight. */
    extendedStructuredCap: Int = 0,
    /** **Extended repairs** (opt-in): also draw each violated factor's opt-in
     *  `proposeExtendedRepairMoves` (e.g. a Regular DP-optimal accepting run) during the
     *  infeasibility fight. */
    extendedRepair: Boolean = false,
): SourceDrivenStrategy {
    val sources = buildList {
        add(ConfiguredSource(ViolatedRepairs(violatedSampleCount)))
        add(ConfiguredSource(Frontier(violatedSampleCount, frontierMoveCap), stallGated = true))
        add(ConfiguredSource(SatisfiedStructured.sampled(satisfiedSampleCount)))
        if (implicitStructuredCap > 0) {
            // Elected implicit globals are drawn during the infeasibility fight, not at feasibility
            // where the satisfied-structured source owns the pool, so override the phase.
            add(ConfiguredSource(SatisfiedStructured.elected(implicitStructuredCap), phase = Phase.Infeasible))
        }
        add(ConfiguredSource(ObjectiveSeed()))
        if (stallSwapCap > 0) add(ConfiguredSource(StallSwaps(stallSwapCap), stallGated = true))
        if (stallChainCap > 0) {
            add(ConfiguredSource(EjectionChains(stallChainCap, stallChainDepth), stallGated = true))
        }
        if (stallCliqueSwapCap > 0) add(ConfiguredSource(CliqueSwap(stallCliqueSwapCap), stallGated = true))
        if (flipPropagateCap > 0) {
            add(ConfiguredSource(FlipAndPropagate(flipPropagateCap, flipPropagateDepth), stallGated = true))
        }
        if (pairSwapHotSpotCap > 0) add(ConfiguredSource(PairSwap.hotSpot(pairSwapHotSpotCap)))
        if (extendedStructuredCap > 0) {
            add(ConfiguredSource(SatisfiedStructured.sampledExtended(extendedStructuredCap)))
            add(ConfiguredSource(SatisfiedStructured.electedExtended(extendedStructuredCap), phase = Phase.Infeasible))
        }
        if (extendedRepair) {
            add(
                ConfiguredSource(ViolatedRepairs.extended(violatedSampleCount), phase = Phase.Infeasible),
            )
        }
    }
    return SourceDrivenStrategy(
        sources = sources,
        scoring = scoring,
        acceptance = AcceptanceRule.WalkSatNoise(noiseProbability),
        schedule = ScheduleBundle(
            weights = WeightSchedule.cbls(
                stallSteps = stallSteps,
                stallIncrement = stallIncrement,
                smoothProb = smoothProb,
                smoothFactor = smoothFactor,
                baseWeight = baseWeight,
            ),
            noise = StallSchedule(frontierAfterStall, noiseProbability, stallNoise),
        ),
        tabu = tabu,
        perturbation = if (stallKickAfter > 0) StallKickPerturbation(stallKickAfter, stallKickVars) else null,
        drivesObjectiveDescent = true,
    )
}

/**
 * The CBLS targeted kick as a driver perturbation hook: after [after] applied moves with no strict
 * cost drop, inject one bounded [StallKick] perturbation and restart the no-progress window. Stateful
 * (one per search); returns `null` while feasible, before the window elapses, or when no variable is
 * eligible.
 */
internal class StallKickPerturbation(private val after: Int, stallKickVars: Int) : (LocalSearchState) -> Move? {
    init {
        require(after >= 1) { "after ≥ 1, got $after" }
    }

    private val stallKick = StallKick(stallKickVars)
    private val kickSink = MoveSink()
    private var lastSeenStep: Long = -1L
    private var lastDropStep: Long = 0L
    private var lastCost: Long = Long.MAX_VALUE

    override fun invoke(state: LocalSearchState): Move? {
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
        if (state.cost <= 0L || state.step - lastDropStep < after) return null
        kickSink.clear()
        stallKick.generate(state, kickSink)
        val kick = kickSink.list.firstOrNull() ?: return null
        lastDropStep = state.step // fresh window after the perturbation
        return kick
    }
}
