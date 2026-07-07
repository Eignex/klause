package com.eignex.klause.portfolio

import com.eignex.klause.localsearch.AcceptanceCriterion
import com.eignex.klause.localsearch.AdaptivePerturbationRestart
import com.eignex.klause.localsearch.AspirationCriterion
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.IteratedLocalSearchRestart
import com.eignex.klause.localsearch.LubyRestart
import com.eignex.klause.localsearch.PerturbationKind
import com.eignex.klause.localsearch.RestartPolicy
import com.eignex.klause.localsearch.TabuFilter
import com.eignex.klause.localsearch.schedule.Geometric
import com.eignex.klause.localsearch.schedule.LoopSchedule
import com.eignex.klause.localsearch.schedule.Reheating
import com.eignex.klause.localsearch.schedule.Schedule
import com.eignex.klause.localsearch.schedule.Segment
import com.eignex.klause.localsearch.scoring.MoveScoring
import com.eignex.klause.localsearch.strategy.Cbls
import com.eignex.klause.localsearch.strategy.FeasibilityJump
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.localsearch.strategy.ProbSat
import com.eignex.klause.localsearch.strategy.SimulatedAnnealing
import com.eignex.klause.localsearch.strategy.SourceDrivenStrategy
import com.eignex.klause.localsearch.strategy.WalkSat
import com.eignex.klause.util.ArmCatalog

/**
 * The curated catalog of local-search arms — the recipes the `ls` portfolio races and the named base
 * recipes the CLI selects. Each arm is a [SourceDrivenStrategy] over the shared driver; the catalog
 * owns their credit-ranked order and the single string boundary ([byLabel]).
 *
 * Factories, not shared instances: strategies carry mutable per-search state (CBLS stall trackers,
 * sinks), so every portfolio slot must get fresh objects — sharing one across two parallel workers is
 * a data race.
 */
object LocalSearchCatalog {
    private fun cblsTabu() = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)

    private fun ilsBasin() = IteratedLocalSearchRestart(
        populationSize = 3,
        crossoverRate = 0.25,
        perturbationKind = PerturbationKind.BasinHopping,
        acceptance = AcceptanceCriterion.Improving,
    )

    /** ILS basin-hopping whose accept/reject is driven by the contextual acceptance bandit — learns
     *  when drifting through worse optima pays off, rather than the fixed improving-only rule. Fresh
     *  bandit per slot. */
    private fun ilsBandit() = IteratedLocalSearchRestart(
        populationSize = 3,
        crossoverRate = 0.25,
        perturbationKind = PerturbationKind.BasinHopping,
        acceptanceBandit = IteratedLocalSearchRestart.acceptanceBandit(),
    )

    /** Fold a restart cadence into a strategy's schedule axis. */
    private fun SourceDrivenStrategy.withRestart(restart: RestartPolicy): SourceDrivenStrategy =
        copy(schedule = schedule.copy(restart = restart))

    /** A CBLS recipe with the unified minimize path: [make] is invoked twice so the satisfy and
     *  optimize strategies are independent instances (CBLS carries per-search state). */
    private fun cblsRecipe(
        label: String,
        restart: RestartPolicy,
        perMoveInvariants: Boolean = true,
        seedImplicitOnRestart: Boolean = false,
        make: () -> SourceDrivenStrategy,
    ) = LocalSearchRecipe(
        label,
        make().withRestart(restart),
        optimizeStrategy = make().withRestart(restart),
        perMoveInvariants = perMoveInvariants,
        seedImplicitOnRestart = seedImplicitOnRestart,
    )

    /** An SA recipe on the unified minimize path so Metropolis anneals on the objective at feasibility
     *  (rather than bailing to the engine's greedy descent); on a CSP the minimize path is unused, so
     *  this is identical to plain SA. [makeSchedule] is invoked twice so the satisfy and optimize
     *  strategies get independent temperature schedules (a [Geometric] carries mutable per-search state). */
    private fun saRecipe(label: String, restart: RestartPolicy, makeSchedule: () -> Schedule) = LocalSearchRecipe(
        label,
        SimulatedAnnealing.optimizer(makeSchedule()).withRestart(restart),
        optimizeStrategy = SimulatedAnnealing.optimizer(makeSchedule()).withRestart(restart),
    )

    /**
     * Fresh [LocalSearchRecipe] for a typed [LocalSearchArm] — the catalog's single factory. Exhaustive `when` so every
     * arm in [LocalSearchArm] must have a factory (and conversely every factory a typed arm); the per-arm
     * comment is the credit-campaign provenance.
     */
    private fun make(arm: LocalSearchArm): LocalSearchRecipe = when (arm) {
        // The constraint-based workhorse; fastest first-incumbent (median 4 ms).
        LocalSearchArm.CblsFixed -> cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(tabu = cblsTabu()) }

        // Adaptive probSAT: biggest marginal adder (+16 uncovered, +9 best) — many flattened
        // Challenge models expose a large boolean core.
        LocalSearchArm.AdaptiveProbsatFixed ->
            LocalSearchRecipe(arm.label, ProbSat.adaptive(tabu = cblsTabu()).withRestart(FixedCadenceRestart()))

        // Plateau-buster (Cbls.stallSwapCap) on the ILS basin-hopping restart: the best plateau
        // variant (+9 uncovered, +5 best).
        LocalSearchArm.CblsPlateauIlsBasin ->
            cblsRecipe(arm.label, ilsBasin()) { Cbls(stallSwapCap = 16, tabu = cblsTabu()) }

        // Ejection chains (Cbls.stallChainCap) + targeted kick — the principled plateau escape.
        // Sweep-off (perMoveInvariants = false): defined vars re-enter the move space, the niche
        // cyclic-definitional successor encodings need. Deep-runway cadence: the dismantle threads at
        // 21k–214k flips, so the default 10k cadence cuts every walk short.
        LocalSearchArm.CblsChainNoinvFixed -> cblsRecipe(
            arm.label,
            FixedCadenceRestart(maxFlipsBeforeRestart = 1_000_000),
            perMoveInvariants = false,
        ) { Cbls(stallChainCap = 8, stallChainDepth = 16, tabu = cblsTabu()) }

        // Ejection chains on the ILS basin-hopping restart with invariants on (the most seed-stable
        // adder in the pool: +3 uncovered, +9 best-held at both campaign seeds).
        LocalSearchArm.CblsChainIlsBasin ->
            cblsRecipe(arm.label, ilsBasin()) { Cbls(stallChainCap = 8, stallChainDepth = 16, tabu = cblsTabu()) }

        // Plateau-buster + smoothing (+5 uncovered, +2 best).
        LocalSearchArm.CblsPlateauSmoothFixed -> cblsRecipe(arm.label, FixedCadenceRestart()) {
            Cbls(stallSwapCap = 16, smoothProb = 0.4, smoothFactor = 0.8, tabu = cblsTabu())
        }

        // Plateau-buster on the fixed cadence (+3 uncovered incl. the bacp-class sole win).
        LocalSearchArm.CblsPlateauFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(stallSwapCap = 16, tabu = cblsTabu()) }

        // Weight forgetting + basin hopping (+2 uncovered, +3 best).
        LocalSearchArm.CblsSmoothIlsBasin ->
            cblsRecipe(arm.label, ilsBasin()) { Cbls(smoothProb = 0.4, smoothFactor = 0.8, tabu = cblsTabu()) }

        // Annealing + adaptive perturbation: the quality closer — adds no coverage but holds the final
        // best on 7 instances, the second-highest in the pool.
        LocalSearchArm.SaAdaptivePerturb -> saRecipe(arm.label, AdaptivePerturbationRestart()) { Geometric() }

        // Patient stall cadence (+1 uncovered, +3 best, one sole win).
        LocalSearchArm.CblsStallslowFixed -> cblsRecipe(arm.label, FixedCadenceRestart()) {
            Cbls(frontierAfterStall = 160, stallNoise = 0.2, tabu = cblsTabu())
        }

        // Cold noise (+1 uncovered, +3 best).
        LocalSearchArm.CblsLonoiseFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(noiseProbability = 0.01, tabu = cblsTabu()) }

        // WalkSAT + configuration checking (+1 uncovered, +2 best; structured-SAT niche).
        LocalSearchArm.WalksatCcLuby -> LocalSearchRecipe(
            arm.label,
            WalkSat(configurationChecking = true, tabu = TabuFilter(tenure = 5)).withRestart(LubyRestart(unit = 200)),
        )

        // Hot noise (+1 uncovered, +1 best).
        LocalSearchArm.CblsHinoiseFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(noiseProbability = 0.15, tabu = cblsTabu()) }

        // --- tail: raw credit only; marginally redundant given the arms above ---
        // Tabu-free CBLS: high raw credit (4 firsts / 247 improvements) but +0 uncovered.
        LocalSearchArm.CblsNotabuFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(tabu = TabuFilter.Disabled) }

        // Plain annealing: 5 raw firsts, all on instances the arms above also solve.
        LocalSearchArm.SaFixed -> saRecipe(
            arm.label,
            FixedCadenceRestart(maxFlipsBeforeRestart = 50_000),
        ) { Geometric() }

        // Aggressive swap cap (raw 1/2, 191 improvements).
        LocalSearchArm.CblsPlateau64Fixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(stallSwapCap = 64, tabu = cblsTabu()) }

        // Raw (unweighted) scoring (raw 2/1).
        LocalSearchArm.CblsRawFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(scoring = MoveScoring.Raw, tabu = cblsTabu()) }

        // Short tabu tenure (raw 2/0, 127 improvements).
        LocalSearchArm.CblsTenure3Fixed -> cblsRecipe(arm.label, FixedCadenceRestart()) {
            Cbls(tabu = TabuFilter(tenure = 3, aspiration = AspirationCriterion.OrImproving))
        }

        // Contextual-bandit ILS acceptance: CBLS on a basin-hopping ILS restart whose accept/reject is
        // learned.
        LocalSearchArm.CblsIlsBandit -> cblsRecipe(arm.label, ilsBandit()) { Cbls(tabu = cblsTabu()) }

        // Bandit-adaptive probSAT: a UCB1 bandit picks the cb noise schedule per session.
        LocalSearchArm.ProbsatBanditFixed ->
            LocalSearchRecipe(arm.label, ProbSat.bandit(tabu = cblsTabu()).withRestart(FixedCadenceRestart()))

        // Implicit-solving neighbourhoods: seed elected structural globals (all-different / inverse /
        // table) feasible on every restart and draw their feasibility-preserving moves during the
        // infeasibility fight. The permutation/assignment-shaped niche.
        LocalSearchArm.CblsImplicitFixed -> cblsRecipe(arm.label, FixedCadenceRestart(), seedImplicitOnRestart = true) {
            Cbls(implicitStructuredCap = 8, tabu = cblsTabu())
        }

        // Clique-swap arm: stall-gated at-most-one clique swaps for packing/assignment cliques whose
        // categorical "which member is on" choice a single flip can only relocate by passing through
        // the doubly-on violating state. Kept last pending its cross-seed credit pass.
        LocalSearchArm.CblsCliqueFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(stallCliqueSwapCap = 8, tabu = cblsTabu()) }

        // Feasibility-Jump arm: a weighted-violation argmin-jump strategy, orthogonal to the
        // step-based CBLS/WalkSAT/SA arms. Violation-native (returns null at feasibility), so on a COP
        // the portfolio's objective-bound ratchet drives its optimize phase, exactly like probSAT/WalkSAT.
        LocalSearchArm.FeasibilityJumpFixed ->
            LocalSearchRecipe(arm.label, FeasibilityJump().withRestart(FixedCadenceRestart()))

        // Flip-and-propagate: stall-gated implication-aware flip compounds (a seed flip bundled with
        // the literals it forces through the binary-implication graph). The boolean-core niche where a
        // flip cascades through binary implications the search would otherwise repair one step at a time.
        LocalSearchArm.CblsFlipPropFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(flipPropagateCap = 8, tabu = cblsTabu()) }

        // Objective-hot-spot pair swaps: objective-descent pair swaps whose first endpoint is drawn
        // from the objective gradient, concentrating coordinated moves on objective-relevant
        // variables. The objective-heavy niche. Kept last pending its cross-seed credit pass.
        LocalSearchArm.CblsHotpairFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(pairSwapHotSpotCap = 8, tabu = cblsTabu()) }

        // Implicit-solving + the opt-in extended structured/repair moves (circuit 2-opt, all-different
        // 3-cycle, Regular DP-repair). A niche layered on cbls-implicit; bench-gated, kept last.
        LocalSearchArm.CblsExtendedFixed -> cblsRecipe(arm.label, FixedCadenceRestart(), seedImplicitOnRestart = true) {
            Cbls(implicitStructuredCap = 8, extendedStructuredCap = 8, extendedRepair = true, tabu = cblsTabu())
        }

        // SA with periodic reheating: the schedule re-diversifies a cooled-and-stuck run without
        // discarding the incumbent. Restart epoch (100k) spans several reheat periods (20k) so the
        // reheats fire before a restart resets the schedule.
        LocalSearchArm.SaReheatFixed -> saRecipe(arm.label, FixedCadenceRestart(maxFlipsBeforeRestart = 100_000)) {
            Reheating(Geometric(), period = 20_000, reheatFactor = 4.0)
        }

        // SA with an explore→exploit phased schedule: a hot, fast-cooling exploratory leg then a cool,
        // slow-cooling exploitative leg, looped. Distinct landscape coverage from the fixed-rate arms.
        LocalSearchArm.SaPhasedFixed -> saRecipe(arm.label, FixedCadenceRestart(maxFlipsBeforeRestart = 100_000)) {
            LoopSchedule(
                listOf(
                    Segment(Geometric(initialTemperature = 2.0, coolingRate = 0.99), steps = 10_000),
                    Segment(Geometric(initialTemperature = 0.3, coolingRate = 0.9995), steps = 40_000),
                ),
            )
        }
    }

    /**
     * COP pool order by cross-seed combined marginal credit (two campaigns at seeds 1/2 — 91 mzn-bench
     * optimization instances, 10 s; score = Σ uncovered + 0.5·Σ best-held; cbls/fixed anchored first as
     * the satisfy workhorse). [diverse] takes a prefix, so `-p <n>` gets the measured-best arms first.
     * Re-derive by re-running the credit campaign at two seeds and editing this one list.
     */
    private val copOrder: List<LocalSearchArm> = listOf(
        LocalSearchArm.CblsFixed,
        LocalSearchArm.CblsPlateauIlsBasin,
        LocalSearchArm.CblsSmoothIlsBasin,
        LocalSearchArm.SaAdaptivePerturb,
        LocalSearchArm.CblsChainIlsBasin,
        LocalSearchArm.CblsChainNoinvFixed,
        LocalSearchArm.CblsNotabuFixed,
        LocalSearchArm.CblsLonoiseFixed,
        LocalSearchArm.AdaptiveProbsatFixed,
        LocalSearchArm.CblsTenure3Fixed,
        LocalSearchArm.CblsStallslowFixed,
        LocalSearchArm.SaFixed,
        LocalSearchArm.CblsPlateau64Fixed,
        LocalSearchArm.WalksatCcLuby,
        LocalSearchArm.CblsHinoiseFixed,
        LocalSearchArm.CblsPlateauSmoothFixed,
        LocalSearchArm.CblsPlateauFixed,
        LocalSearchArm.CblsRawFixed,
        // Bandit candidates; kept last so the default diverse(N) prefix is unchanged.
        LocalSearchArm.CblsIlsBandit,
        LocalSearchArm.ProbsatBanditFixed,
        // Implicit-solving niche; kept last pending a cross-seed credit pass.
        LocalSearchArm.CblsImplicitFixed,
        // Clique-swap niche; kept last pending a cross-seed credit pass.
        LocalSearchArm.CblsCliqueFixed,
        // Feasibility-Jump arm; kept last pending its cross-seed credit pass.
        LocalSearchArm.FeasibilityJumpFixed,
        // Implication-aware flip niche; kept last pending its cross-seed credit pass.
        LocalSearchArm.CblsFlipPropFixed,
        // Objective-hot-spot pair-swap niche; kept last pending its cross-seed credit pass.
        LocalSearchArm.CblsHotpairFixed,
        // Extended structured/repair moves niche; kept last pending its cross-seed credit pass.
        LocalSearchArm.CblsExtendedFixed,
        // Schedule-diversity SA arms; kept last pending their cross-seed credit pass.
        LocalSearchArm.SaReheatFixed,
        LocalSearchArm.SaPhasedFixed,
    )

    /**
     * CSP pool order: [copOrder] minus the arms that do nothing without an objective — mirroring how
     * [BacktrackCatalog] drops its LP / LinUCB arms on a CSP. Only [LocalSearchArm.CblsHotpairFixed]
     * (objective-hot-spot pair swaps, whose endpoints are drawn from the objective gradient) degenerates
     * without one; every other arm fights infeasibility, so it stays. Relative order is preserved
     * pending a dedicated CSP credit campaign.
     */
    private val cspOrder: List<LocalSearchArm> = copOrder.filter { it != LocalSearchArm.CblsHotpairFixed }

    private fun rankedArms(kind: Kind): List<LocalSearchArm> = when (kind) {
        Kind.COP -> copOrder
        Kind.CSP -> cspOrder
    }

    /** The shared string-boundary / order-driven accessors (see [ArmCatalog]); this catalog supplies
     *  the per-[Kind] order ([rankedArms]) to the pool builders. */
    private val catalog = ArmCatalog(LocalSearchArm.entries, LocalSearchArm::label, ::make)

    /** A fresh recipe for the arm named [label] (the single string boundary). */
    fun byLabel(label: String): LocalSearchRecipe = catalog.byLabel(label)

    /** Every arm label across both kinds (COP is the superset), for validation and error messages. */
    fun labels(): List<String> = catalog.labels(copOrder)

    /** Every arm label for [kind], in credit order — for enumerating the pool by name. */
    fun labels(kind: Kind): List<String> = catalog.labels(rankedArms(kind))

    /** One fresh recipe for every arm of [kind], in credit order. */
    fun ranked(kind: Kind): List<LocalSearchRecipe> = catalog.ranked(rankedArms(kind))

    /** Per-arm factories for [kind], in credit order — each builds a *fresh* recipe (the strategies
     *  carry mutable per-search state, so every portfolio slot must get its own). */
    fun factories(kind: Kind): List<() -> LocalSearchRecipe> = catalog.factories(rankedArms(kind))

    /** The top-[count] prefix of [kind]'s credit-ordered pool (wrapping past the pool size). Every slot
     *  is a fresh instance even when arms repeat. */
    fun diverse(kind: Kind, count: Int): List<LocalSearchRecipe> {
        require(count >= 1) { "count must be ≥ 1" }
        val order = rankedArms(kind)
        return List(count) { make(order[it % order.size]) }
    }
}

/**
 * Typed identity of every catalog arm — the catalog's keys. [LocalSearchCatalog.ranked] /
 * [LocalSearchCatalog.diverse] order and instantiate these per [Kind] via [LocalSearchCatalog]; [label]
 * is the external name (CLI / campaign / telemetry).
 */
internal enum class LocalSearchArm(val label: String) {
    CblsFixed("cbls/fixed"),
    AdaptiveProbsatFixed("adaptive-probsat/fixed"),
    CblsPlateauIlsBasin("cbls-plateau/ils-basin"),
    CblsChainNoinvFixed("cbls-chain-noinv/fixed"),
    CblsChainIlsBasin("cbls-chain/ils-basin"),
    CblsPlateauSmoothFixed("cbls-plateau-smooth/fixed"),
    CblsPlateauFixed("cbls-plateau/fixed"),
    CblsSmoothIlsBasin("cbls-smooth/ils-basin"),
    SaAdaptivePerturb("sa/adaptive-perturb"),
    CblsStallslowFixed("cbls-stallslow/fixed"),
    CblsLonoiseFixed("cbls-lonoise/fixed"),
    WalksatCcLuby("walksat-cc/luby"),
    CblsHinoiseFixed("cbls-hinoise/fixed"),
    CblsNotabuFixed("cbls-notabu/fixed"),
    SaFixed("sa/fixed"),
    CblsPlateau64Fixed("cbls-plateau64/fixed"),
    CblsRawFixed("cbls-raw/fixed"),
    CblsTenure3Fixed("cbls-tenure3/fixed"),
    CblsIlsBandit("cbls/ils-bandit"),
    ProbsatBanditFixed("probsat-bandit/fixed"),
    CblsImplicitFixed("cbls-implicit/fixed"),
    CblsCliqueFixed("cbls-clique/fixed"),
    FeasibilityJumpFixed("fjump/fixed"),
    CblsFlipPropFixed("cbls-flipprop/fixed"),
    CblsHotpairFixed("cbls-hotpair/fixed"),
    CblsExtendedFixed("cbls-extended/fixed"),
    SaReheatFixed("sa-reheat/fixed"),
    SaPhasedFixed("sa-phased/fixed"),
}
