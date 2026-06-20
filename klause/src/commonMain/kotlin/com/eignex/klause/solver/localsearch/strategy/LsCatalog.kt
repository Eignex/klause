package com.eignex.klause.solver.localsearch.strategy

import com.eignex.klause.solver.localsearch.AcceptanceCriterion
import com.eignex.klause.solver.localsearch.AdaptivePerturbationRestart
import com.eignex.klause.solver.localsearch.AspirationCriterion
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.IteratedLocalSearchRestart
import com.eignex.klause.solver.localsearch.LubyRestart
import com.eignex.klause.solver.localsearch.PerturbationKind
import com.eignex.klause.solver.localsearch.RestartPolicy
import com.eignex.klause.solver.localsearch.TabuFilter
import com.eignex.klause.solver.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.solver.localsearch.movesource.ConfiguredSource
import com.eignex.klause.solver.localsearch.schedule.Geometric
import com.eignex.klause.solver.localsearch.schedule.LoopSchedule
import com.eignex.klause.solver.localsearch.schedule.Reheating
import com.eignex.klause.solver.localsearch.schedule.Schedule
import com.eignex.klause.solver.localsearch.schedule.Segment
import com.eignex.klause.solver.localsearch.scoring.MoveScoring

/**
 * A named local-search recipe: a four-axis [SourceDrivenStrategy] (its restart cadence carried in the
 * schedule axis, [com.eignex.klause.solver.localsearch.schedule.ScheduleBundle.restart]) plus the few
 * solver-level knobs the strategy itself doesn't own. The portfolio wraps one of these per arm; a
 * single recipe is just a portfolio of one.
 */
class LsRecipe(
    /** External name (CLI / campaign / telemetry). */
    val label: String,
    /** Drives the feasibility fight (and, when [SourceDrivenStrategy.drivesObjectiveDescent], the
     *  objective descent too). Restart lives in its `schedule.restart`. */
    val strategy: SourceDrivenStrategy,
    /** Minimize-phase strategy; `null` lets the engine's built-in objective descent own the optimize
     *  phase. A CBLS arm sets this to a second CBLS instance for the unified minimize path. */
    val optimizeStrategy: SourceDrivenStrategy? = null,
    /** Per-arm switch for the per-move invariant network; off carves a diversity niche for cyclic
     *  definitional encodings whose reified indicators are otherwise search-excluded. */
    val perMoveInvariants: Boolean = true,
    /** Per-arm switch for implicit-solving feasible init on every restart (paired with a CBLS whose
     *  `implicitStructuredCap > 0` on permutation/assignment-shaped models). */
    val seedImplicitOnRestart: Boolean = false,
) {
    /** A copy with [transform] applied to the satisfy strategy and the optimize strategy (if any), so
     *  one axis edit rewrites both halves of the recipe consistently. */
    private inline fun mapStrategies(transform: (SourceDrivenStrategy) -> SourceDrivenStrategy): LsRecipe =
        LsRecipe(label, transform(strategy), optimizeStrategy?.let(transform), perMoveInvariants, seedImplicitOnRestart)

    /** A copy whose sources axis is [transform]ed (the editable list of configured move sources). */
    fun withSources(transform: (List<ConfiguredSource>) -> List<ConfiguredSource>): LsRecipe =
        mapStrategies { it.copy(sources = transform(it.sources)) }

    /** A copy whose scoring axis is replaced. */
    fun withScoring(scoring: MoveScoring): LsRecipe = mapStrategies { it.copy(scoring = scoring) }

    /** A copy whose acceptance axis is replaced. */
    fun withAcceptance(acceptance: AcceptanceRule): LsRecipe = mapStrategies { it.copy(acceptance = acceptance) }

    /** A copy whose restart cadence (the schedule axis's restart member) is replaced. */
    fun withRestart(restart: RestartPolicy): LsRecipe =
        mapStrategies { it.copy(schedule = it.schedule.copy(restart = restart)) }

    /** A copy whose schedule-axis temperature is replaced — used to attach a cooling schedule when an
     *  acceptance edit turns a recipe into simulated annealing but it carried no temperature. */
    fun withTemperature(temperature: Schedule): LsRecipe =
        mapStrategies { it.copy(schedule = it.schedule.copy(temperature = temperature)) }
}

/**
 * The curated catalog of local-search arms — the recipes the `ls` portfolio races and the named base
 * recipes the CLI selects. Each arm is a [SourceDrivenStrategy] over the shared driver; the catalog
 * owns their credit-ranked order and the single string boundary ([byLabel]).
 *
 * Factories, not shared instances: strategies carry mutable per-search state (CBLS stall trackers,
 * sinks), so every portfolio slot must get fresh objects — sharing one across two parallel workers is
 * a data race.
 */
object LsCatalog {
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
    ) = LsRecipe(
        label,
        make().withRestart(restart),
        optimizeStrategy = make().withRestart(restart),
        perMoveInvariants = perMoveInvariants,
        seedImplicitOnRestart = seedImplicitOnRestart,
    )

    /**
     * Fresh [LsRecipe] for a typed [LsArm] — the catalog's single factory. Exhaustive `when` so every
     * arm in [LsArm] must have a factory (and conversely every factory a typed arm); the per-arm
     * comment is the credit-campaign provenance.
     */
    private fun make(arm: LsArm): LsRecipe = when (arm) {
        // The constraint-based workhorse; fastest first-incumbent (median 4 ms).
        LsArm.CblsFixed -> cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(tabu = cblsTabu()) }

        // Adaptive probSAT: biggest marginal adder (+16 uncovered, +9 best) — many flattened
        // Challenge models expose a large boolean core.
        LsArm.AdaptiveProbsatFixed ->
            LsRecipe(arm.label, ProbSat.adaptive(tabu = cblsTabu()).withRestart(FixedCadenceRestart()))

        // Plateau-buster (Cbls.stallSwapCap) on the ILS basin-hopping restart: the best plateau
        // variant (+9 uncovered, +5 best).
        LsArm.CblsPlateauIlsBasin ->
            cblsRecipe(arm.label, ilsBasin()) { Cbls(stallSwapCap = 16, tabu = cblsTabu()) }

        // Ejection chains (Cbls.stallChainCap) + targeted kick — the principled plateau escape.
        // Sweep-off (perMoveInvariants = false): defined vars re-enter the move space, the niche
        // cyclic-definitional successor encodings need. Deep-runway cadence: the dismantle threads at
        // 21k–214k flips, so the default 10k cadence cuts every walk short.
        LsArm.CblsChainNoinvFixed -> cblsRecipe(
            arm.label,
            FixedCadenceRestart(maxFlipsBeforeRestart = 1_000_000),
            perMoveInvariants = false,
        ) { Cbls(stallChainCap = 8, stallChainDepth = 16, tabu = cblsTabu()) }

        // Ejection chains on the ILS basin-hopping restart with invariants on (the most seed-stable
        // adder in the pool: +3 uncovered, +9 best-held at both campaign seeds).
        LsArm.CblsChainIlsBasin ->
            cblsRecipe(arm.label, ilsBasin()) { Cbls(stallChainCap = 8, stallChainDepth = 16, tabu = cblsTabu()) }

        // Plateau-buster + smoothing (+5 uncovered, +2 best).
        LsArm.CblsPlateauSmoothFixed -> cblsRecipe(arm.label, FixedCadenceRestart()) {
            Cbls(stallSwapCap = 16, smoothProb = 0.4, smoothFactor = 0.8, tabu = cblsTabu())
        }

        // Plateau-buster on the fixed cadence (+3 uncovered incl. the bacp-class sole win).
        LsArm.CblsPlateauFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(stallSwapCap = 16, tabu = cblsTabu()) }

        // Weight forgetting + basin hopping (+2 uncovered, +3 best).
        LsArm.CblsSmoothIlsBasin ->
            cblsRecipe(arm.label, ilsBasin()) { Cbls(smoothProb = 0.4, smoothFactor = 0.8, tabu = cblsTabu()) }

        // Annealing + adaptive perturbation: the quality closer — adds no coverage but holds the final
        // best on 7 instances, the second-highest in the pool.
        LsArm.SaAdaptivePerturb ->
            LsRecipe(arm.label, SimulatedAnnealing().withRestart(AdaptivePerturbationRestart()))

        // Patient stall cadence (+1 uncovered, +3 best, one sole win).
        LsArm.CblsStallslowFixed -> cblsRecipe(arm.label, FixedCadenceRestart()) {
            Cbls(frontierAfterStall = 160, stallNoise = 0.2, tabu = cblsTabu())
        }

        // Cold noise (+1 uncovered, +3 best).
        LsArm.CblsLonoiseFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(noiseProbability = 0.01, tabu = cblsTabu()) }

        // WalkSAT + configuration checking (+1 uncovered, +2 best; structured-SAT niche).
        LsArm.WalksatCcLuby -> LsRecipe(
            arm.label,
            WalkSat(configurationChecking = true, tabu = TabuFilter(tenure = 5)).withRestart(LubyRestart(unit = 200)),
        )

        // Hot noise (+1 uncovered, +1 best).
        LsArm.CblsHinoiseFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(noiseProbability = 0.15, tabu = cblsTabu()) }

        // --- tail: raw credit only; marginally redundant given the arms above ---
        // Tabu-free CBLS: high raw credit (4 firsts / 247 improvements) but +0 uncovered.
        LsArm.CblsNotabuFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(tabu = TabuFilter.Disabled) }

        // Plain annealing: 5 raw firsts, all on instances the arms above also solve.
        LsArm.SaFixed ->
            LsRecipe(arm.label, SimulatedAnnealing().withRestart(FixedCadenceRestart(maxFlipsBeforeRestart = 50_000)))

        // Aggressive swap cap (raw 1/2, 191 improvements).
        LsArm.CblsPlateau64Fixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(stallSwapCap = 64, tabu = cblsTabu()) }

        // Raw (unweighted) scoring (raw 2/1).
        LsArm.CblsRawFixed ->
            cblsRecipe(arm.label, FixedCadenceRestart()) { Cbls(scoring = MoveScoring.Raw, tabu = cblsTabu()) }

        // Short tabu tenure (raw 2/0, 127 improvements).
        LsArm.CblsTenure3Fixed -> cblsRecipe(arm.label, FixedCadenceRestart()) {
            Cbls(tabu = TabuFilter(tenure = 3, aspiration = AspirationCriterion.OrImproving))
        }

        // Contextual-bandit ILS acceptance: CBLS on a basin-hopping ILS restart whose accept/reject is
        // learned.
        LsArm.CblsIlsBandit -> cblsRecipe(arm.label, ilsBandit()) { Cbls(tabu = cblsTabu()) }

        // Bandit-adaptive probSAT: a UCB1 bandit picks the cb noise schedule per session.
        LsArm.ProbsatBanditFixed ->
            LsRecipe(arm.label, ProbSat.bandit(tabu = cblsTabu()).withRestart(FixedCadenceRestart()))

        // Implicit-solving neighbourhoods: seed elected structural globals (all-different / inverse /
        // table) feasible on every restart and draw their feasibility-preserving moves during the
        // infeasibility fight. The permutation/assignment-shaped niche.
        LsArm.CblsImplicitFixed -> cblsRecipe(arm.label, FixedCadenceRestart(), seedImplicitOnRestart = true) {
            Cbls(implicitStructuredCap = 8, tabu = cblsTabu())
        }

        // Feasibility-Jump arm: a weighted-violation argmin-jump strategy, orthogonal to the
        // step-based CBLS/WalkSAT/SA arms. It drives the feasibility fight and returns null at
        // feasibility, so the engine's built-in objective descent owns the optimize phase.
        LsArm.FeasibilityJumpFixed ->
            LsRecipe(arm.label, FeasibilityJump().withRestart(FixedCadenceRestart()))

        // SA with periodic reheating: the schedule re-diversifies a cooled-and-stuck run without
        // discarding the incumbent. Restart epoch (100k) spans several reheat periods (20k) so the
        // reheats fire before a restart resets the schedule.
        LsArm.SaReheatFixed -> LsRecipe(
            arm.label,
            SimulatedAnnealing.withSchedule(Reheating(Geometric(), period = 20_000, reheatFactor = 4.0))
                .withRestart(FixedCadenceRestart(maxFlipsBeforeRestart = 100_000)),
        )

        // SA with an explore→exploit phased schedule: a hot, fast-cooling exploratory leg then a cool,
        // slow-cooling exploitative leg, looped. Distinct landscape coverage from the fixed-rate arms.
        LsArm.SaPhasedFixed -> LsRecipe(
            arm.label,
            SimulatedAnnealing.withSchedule(
                LoopSchedule(
                    listOf(
                        Segment(Geometric(initialTemperature = 2.0, coolingRate = 0.99), steps = 10_000),
                        Segment(Geometric(initialTemperature = 0.3, coolingRate = 0.9995), steps = 40_000),
                    ),
                ),
            ).withRestart(FixedCadenceRestart(maxFlipsBeforeRestart = 100_000)),
        )
    }

    /**
     * Pool order by cross-seed combined marginal credit (two campaigns at seeds 1/2 — 91 mzn-bench
     * optimization instances, 10 s; score = Σ uncovered + 0.5·Σ best-held; cbls/fixed anchored first
     * as the satisfy workhorse). [diverse] takes a prefix, so `-p <n>` gets the measured-best arms
     * first. Re-derive by re-running the credit campaign at two seeds and editing this one list.
     */
    private val ranked: List<LsArm> = listOf(
        LsArm.CblsFixed, LsArm.CblsPlateauIlsBasin, LsArm.CblsSmoothIlsBasin, LsArm.SaAdaptivePerturb,
        LsArm.CblsChainIlsBasin, LsArm.CblsChainNoinvFixed, LsArm.CblsNotabuFixed, LsArm.CblsLonoiseFixed,
        LsArm.AdaptiveProbsatFixed, LsArm.CblsTenure3Fixed, LsArm.CblsStallslowFixed, LsArm.SaFixed,
        LsArm.CblsPlateau64Fixed, LsArm.WalksatCcLuby, LsArm.CblsHinoiseFixed, LsArm.CblsPlateauSmoothFixed,
        LsArm.CblsPlateauFixed, LsArm.CblsRawFixed,
        // Bandit candidates; kept last so the default diverse(N) prefix is unchanged.
        LsArm.CblsIlsBandit, LsArm.ProbsatBanditFixed,
        // Implicit-solving niche; kept last pending a cross-seed credit pass.
        LsArm.CblsImplicitFixed,
        // Feasibility-Jump arm; kept last pending its cross-seed credit pass.
        LsArm.FeasibilityJumpFixed,
        // Schedule-diversity SA arms; kept last pending their cross-seed credit pass.
        LsArm.SaReheatFixed, LsArm.SaPhasedFixed,
    )

    private fun fromLabel(label: String): LsArm = LsArm.entries.firstOrNull { it.label == label }
        ?: error("unknown LS arm '$label' (have ${LsArm.entries.joinToString { it.label }})")

    /** A fresh recipe for the arm named [label] (the single string boundary). */
    fun byLabel(label: String): LsRecipe = make(fromLabel(label))

    /** One fresh recipe for every arm, in credit order. */
    fun auto(): List<LsRecipe> = ranked.map { make(it) }

    /** Per-arm factories in credit order — each builds a *fresh* recipe. Lets a caller layer axis
     *  edits over the curated pool while still handing every portfolio slot its own instance (the
     *  strategies carry mutable per-search state, so slots must not share one). */
    fun factories(): List<() -> LsRecipe> = ranked.map { arm -> { make(arm) } }

    /** The top-[count] prefix of the credit-ordered pool (wrapping past the pool size). Every slot is
     *  a fresh instance even when arms repeat. */
    fun diverse(count: Int): List<LsRecipe> {
        require(count >= 1) { "count must be ≥ 1" }
        return List(count) { make(ranked[it % ranked.size]) }
    }
}

/**
 * Typed identity of every catalog arm — the catalog's keys. [LsCatalog.auto] / [LsCatalog.diverse]
 * order and instantiate these via [LsCatalog]; [label] is the external name (CLI / campaign /
 * telemetry).
 */
internal enum class LsArm(val label: String) {
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
    FeasibilityJumpFixed("fjump/fixed"),
    SaReheatFixed("sa-reheat/fixed"),
    SaPhasedFixed("sa-phased/fixed"),
}
