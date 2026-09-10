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

    private fun ilsBandit() = IteratedLocalSearchRestart(
        populationSize = 3,
        crossoverRate = 0.25,
        perturbationKind = PerturbationKind.BasinHopping,
        acceptanceBandit = IteratedLocalSearchRestart.acceptanceBandit(),
    )

    private fun SourceDrivenStrategy.withRestart(restart: RestartPolicy): SourceDrivenStrategy =
        copy(schedule = schedule.copy(restart = restart))

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

    private fun saRecipe(label: String, restart: RestartPolicy, makeSchedule: () -> Schedule) = LocalSearchRecipe(
        label,
        SimulatedAnnealing.optimizer(makeSchedule()).withRestart(restart),
        optimizeStrategy = SimulatedAnnealing.optimizer(makeSchedule()).withRestart(restart),
    )

    private fun make(arm: LocalSearchArm): LocalSearchRecipe = when (arm) {
        LocalSearchArm.CblsFixed -> cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(tabu = cblsTabu()) }

        LocalSearchArm.AdaptiveProbsatFixed ->
            LocalSearchRecipe(arm.label, ProbSat.adaptive(tabu = cblsTabu()).withRestart(FixedCadenceRestart()))

        LocalSearchArm.CblsPlateauIlsBasin ->
            cblsRecipe(arm.label, ilsBasin()) { Cbls(stallSwapCap = 16, tabu = cblsTabu()) }

        LocalSearchArm.CblsChainNoinvFixed -> cblsRecipe(
            arm.label,
            FixedCadenceRestart(maxFlipsBeforeRestart = 1_000_000),
            perMoveInvariants = false,
        ) { Cbls(stallChainCap = 8, stallChainDepth = 16, tabu = cblsTabu()) }

        LocalSearchArm.CblsChainIlsBasin ->
            cblsRecipe(arm.label, ilsBasin()) { Cbls(stallChainCap = 8, stallChainDepth = 16, tabu = cblsTabu()) }

        LocalSearchArm.CblsPlateauSmoothFixed -> cblsRecipe(arm.label, FixedCadenceRestart()) {
            Cbls(stallSwapCap = 16, smoothProb = 0.4, smoothFactor = 0.8, tabu = cblsTabu())
        }

        LocalSearchArm.CblsPlateauFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(stallSwapCap = 16, tabu = cblsTabu()) }

        LocalSearchArm.CblsSmoothIlsBasin ->
            cblsRecipe(arm.label, ilsBasin()) { Cbls(smoothProb = 0.4, smoothFactor = 0.8, tabu = cblsTabu()) }

        LocalSearchArm.SaAdaptivePerturb -> saRecipe(arm.label, AdaptivePerturbationRestart()) { Geometric() }

        LocalSearchArm.CblsStallslowFixed -> cblsRecipe(arm.label, FixedCadenceRestart()) {
            Cbls(frontierAfterStall = 160, stallNoise = 0.2, tabu = cblsTabu())
        }

        LocalSearchArm.CblsLonoiseFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(noiseProbability = 0.01, tabu = cblsTabu()) }

        LocalSearchArm.WalksatCcLuby -> LocalSearchRecipe(
            arm.label,
            WalkSat(configurationChecking = true, tabu = TabuFilter(tenure = 5)).withRestart(LubyRestart(unit = 200)),
        )

        LocalSearchArm.CblsHinoiseFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(noiseProbability = 0.15, tabu = cblsTabu()) }

        LocalSearchArm.CblsNotabuFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(tabu = TabuFilter.Disabled) }

        LocalSearchArm.SaFixed -> saRecipe(
            arm.label,
            FixedCadenceRestart(maxFlipsBeforeRestart = 50_000),
        ) { Geometric() }

        LocalSearchArm.CblsPlateau64Fixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(stallSwapCap = 64, tabu = cblsTabu()) }

        LocalSearchArm.CblsRawFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(scoring = MoveScoring.Raw, tabu = cblsTabu()) }

        LocalSearchArm.CblsTenure3Fixed -> cblsRecipe(arm.label, FixedCadenceRestart()) {
            Cbls(tabu = TabuFilter(tenure = 3, aspiration = AspirationCriterion.OrImproving))
        }

        LocalSearchArm.CblsIlsBandit -> cblsRecipe(arm.label, ilsBandit()) { Cbls(tabu = cblsTabu()) }

        LocalSearchArm.ProbsatBanditFixed ->
            LocalSearchRecipe(arm.label, ProbSat.bandit(tabu = cblsTabu()).withRestart(FixedCadenceRestart()))

        LocalSearchArm.CblsImplicitFixed -> cblsRecipe(arm.label, FixedCadenceRestart(), seedImplicitOnRestart = true) {
            Cbls(implicitStructuredCap = 8, tabu = cblsTabu())
        }

        LocalSearchArm.CblsCliqueFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(stallCliqueSwapCap = 8, tabu = cblsTabu()) }

        LocalSearchArm.FeasibilityJumpFixed ->
            LocalSearchRecipe(arm.label, FeasibilityJump().withRestart(FixedCadenceRestart()))

        LocalSearchArm.CblsFlipPropFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(flipPropagateCap = 8, tabu = cblsTabu()) }

        LocalSearchArm.CblsHotpairFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(pairSwapHotSpotCap = 8, tabu = cblsTabu()) }

        LocalSearchArm.CblsExtendedFixed -> cblsRecipe(arm.label, FixedCadenceRestart(), seedImplicitOnRestart = true) {
            Cbls(implicitStructuredCap = 8, extendedStructuredCap = 8, extendedRepair = true, tabu = cblsTabu())
        }

        LocalSearchArm.SaReheatFixed -> saRecipe(arm.label, FixedCadenceRestart(maxFlipsBeforeRestart = 100_000)) {
            Reheating(Geometric(), period = 20_000, reheatFactor = 4.0)
        }

        LocalSearchArm.SaPhasedFixed -> saRecipe(arm.label, FixedCadenceRestart(maxFlipsBeforeRestart = 100_000)) {
            LoopSchedule(
                listOf(
                    Segment(Geometric(initialTemperature = 2.0, coolingRate = 0.99), steps = 10_000),
                    Segment(Geometric(initialTemperature = 0.3, coolingRate = 0.9995), steps = 40_000),
                ),
            )
        }
    }

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
        LocalSearchArm.CblsIlsBandit,
        LocalSearchArm.ProbsatBanditFixed,
        LocalSearchArm.CblsImplicitFixed,
        LocalSearchArm.CblsCliqueFixed,
        LocalSearchArm.FeasibilityJumpFixed,
        LocalSearchArm.CblsFlipPropFixed,
        LocalSearchArm.CblsHotpairFixed,
        LocalSearchArm.CblsExtendedFixed,
        LocalSearchArm.SaReheatFixed,
        LocalSearchArm.SaPhasedFixed,
    )

    private val cspOrder: List<LocalSearchArm> = copOrder.filter { it != LocalSearchArm.CblsHotpairFixed }

    private fun rankedArms(kind: Kind): List<LocalSearchArm> = when (kind) {
        Kind.COP -> copOrder
        Kind.CSP -> cspOrder
    }

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
