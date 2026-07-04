package com.eignex.klause.localsearch.strategy

import com.eignex.klause.localsearch.TabuFilter
import com.eignex.klause.localsearch.acceptance.AcceptanceRule
import com.eignex.klause.localsearch.movesource.ConfiguredSource
import com.eignex.klause.localsearch.movesource.ObjectiveSeed
import com.eignex.klause.localsearch.movesource.SatisfiedStructured
import com.eignex.klause.localsearch.movesource.ViolatedRepairs
import com.eignex.klause.localsearch.schedule.BanditNoiseController
import com.eignex.klause.localsearch.schedule.Geometric
import com.eignex.klause.localsearch.schedule.NoiseController
import com.eignex.klause.localsearch.schedule.Schedule
import com.eignex.klause.localsearch.schedule.ScheduleBundle
import com.eignex.klause.localsearch.scoring.MoveScoring

/**
 * The focused-LS family — WalkSAT, probSAT, simulated annealing — as [SourceDrivenStrategy] recipes.
 * Each step focuses on a single uniformly-random violated factor's repair suggestions
 * ([ViolatedRepairs] with `sampleCount = 1`, the WalkSAT/probSAT opener) and selects among them by
 * the shaped break score ([MoveScoring.Break]); what differs between the algorithms is the acceptance
 * rule (and, for the adaptive variants, the schedule axis's noise dimension):
 *
 *  - [WalkSat] — [AcceptanceRule.WalkSatNoise]; [WalkSat.adaptive] steers the noise off a noise
 *    schedule (Hoos-2002 adaptive WalkSAT).
 *  - [ProbSat] — [AcceptanceRule.ProbSat]; [ProbSat.adaptive] / [ProbSat.bandit] steer the break
 *    exponent off a noise / bandit schedule.
 *  - [SimulatedAnnealing] — [AcceptanceRule.Metropolis] under a temperature schedule.
 *
 * Configuration checking (CCASat) is opt-in on every factory: it restricts candidates to variables
 * whose configuration changed since their last flip, falling back to the full set when all are
 * CC-blocked.
 */
private fun focusedSources() = listOf(ConfiguredSource(ViolatedRepairs.SINGLE))

/**
 * WalkSAT factory (Selman 1994). `WalkSat(...)` builds the fixed-noise recipe; [adaptive] builds the
 * Hoos-2002 adaptive-noise variant, whose noise level is steered each pick by a [NoiseController] on
 * the schedule axis's noise dimension.
 */
object WalkSat {
    /** Fixed-noise WalkSAT recipe. */
    operator fun invoke(
        noise: Double = 0.5,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        configurationChecking: Boolean = false,
    ): SourceDrivenStrategy = SourceDrivenStrategy(
        sources = focusedSources(),
        scoring = MoveScoring.Break,
        acceptance = AcceptanceRule.WalkSatNoise(noise),
        tabu = tabu,
        configurationChecking = configurationChecking,
        feasibleDescent = FeasibleDescent.RatchetAsConstraint,
    )

    /**
     * Adaptive-noise WalkSAT (Hoos 2002): noise starts at [baselineNoise] and is steered in
     * `[baselineNoise, 1.0]` — climbs on stalls, decays on improvement.
     */
    fun adaptive(
        baselineNoise: Double = 0.2,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        theta: Int = 50,
        phi: Double = 0.2,
        ewmaAlpha: Double? = null,
        configurationChecking: Boolean = false,
    ): SourceDrivenStrategy = SourceDrivenStrategy(
        sources = focusedSources(),
        scoring = MoveScoring.Break,
        acceptance = AcceptanceRule.WalkSatNoise(baselineNoise),
        schedule = ScheduleBundle(
            noise = NoiseController(
                initial = baselineNoise,
                theta = theta,
                phi = phi,
                minLevel = baselineNoise,
                maxLevel = 1.0,
                ewmaAlpha = ewmaAlpha,
            ),
        ),
        tabu = tabu,
        configurationChecking = configurationChecking,
        feasibleDescent = FeasibleDescent.RatchetAsConstraint,
    )
}

/**
 * probSAT factory (Balint & Schöning 2012). `ProbSat(...)` builds the fixed-`cb` recipe; [adaptive] /
 * [bandit] build the variants whose break exponent is steered each pick by a noise / bandit schedule
 * on the schedule axis. Configuration checking is opt-in on all (probSAT + CC is a strong combo on
 * structured instances).
 */
object ProbSat {
    /** Fixed-`cb` probSAT recipe. */
    operator fun invoke(
        cb: Double = 2.06,
        eps: Double = 1.0,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        configurationChecking: Boolean = false,
    ): SourceDrivenStrategy = SourceDrivenStrategy(
        sources = focusedSources(),
        scoring = MoveScoring.Break,
        acceptance = AcceptanceRule.ProbSat(cb, eps),
        tabu = tabu,
        configurationChecking = configurationChecking,
        feasibleDescent = FeasibleDescent.RatchetAsConstraint,
    )

    /**
     * Adaptive-`cb` probSAT: the break-exponent starts at [baselineCb] and is steered down during
     * stalls (distribution flattens toward uniform) and back up on improvement, off a [NoiseController]
     * on the schedule axis.
     */
    fun adaptive(
        baselineCb: Double = 2.06,
        eps: Double = 1.0,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        theta: Int = 50,
        phi: Double = 0.2,
        ewmaAlpha: Double? = null,
        configurationChecking: Boolean = false,
    ): SourceDrivenStrategy = SourceDrivenStrategy(
        sources = focusedSources(),
        scoring = MoveScoring.Break,
        acceptance = AcceptanceRule.ProbSat(baselineCb, eps),
        schedule = ScheduleBundle(
            noise = NoiseController(initial = 0.0, theta = theta, phi = phi, ewmaAlpha = ewmaAlpha),
        ),
        tabu = tabu,
        configurationChecking = configurationChecking,
        feasibleDescent = FeasibleDescent.RatchetAsConstraint,
    )

    /**
     * Bandit-adaptive probSAT: the break-exponent schedule is driven by a [BanditNoiseController] — a
     * kumulant UCB1 bandit over aggressive/moderate/patient bump-on-stall profiles — instead of a
     * single fixed [NoiseController]. The bandit learns per session which profile suits the instance.
     */
    fun bandit(
        baselineCb: Double = 2.06,
        eps: Double = 1.0,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        seed: Long = 0L,
        configurationChecking: Boolean = false,
    ): SourceDrivenStrategy = SourceDrivenStrategy(
        sources = focusedSources(),
        scoring = MoveScoring.Break,
        acceptance = AcceptanceRule.ProbSat(baselineCb, eps),
        schedule = ScheduleBundle(noise = BanditNoiseController.default(baseline = 0.0, seed = seed)),
        tabu = tabu,
        configurationChecking = configurationChecking,
        feasibleDescent = FeasibleDescent.RatchetAsConstraint,
    )
}

/**
 * Simulated-annealing factory: a [SourceDrivenStrategy] recipe with [AcceptanceRule.Metropolis] over
 * a temperature schedule on the schedule axis. Configuration checking is opt-in.
 */
object SimulatedAnnealing {
    /** SA recipe under a fixed geometric cooling schedule. */
    operator fun invoke(
        initialTemperature: Double = 1.0,
        coolingRate: Double = 0.999,
        minTemperature: Double = 0.001,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        configurationChecking: Boolean = false,
    ): SourceDrivenStrategy = withSchedule(
        Geometric(initialTemperature, coolingRate, minTemperature),
        tabu,
        configurationChecking,
    )

    /**
     * Build an SA recipe over an arbitrary temperature [schedule] — geometric, adaptive-cooling, or
     * reheating — so the SA arms can run any schedule through the shared [AcceptanceRule.Metropolis].
     */
    fun withSchedule(
        schedule: Schedule,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        configurationChecking: Boolean = false,
    ): SourceDrivenStrategy = SourceDrivenStrategy(
        sources = focusedSources(),
        scoring = MoveScoring.Break,
        acceptance = AcceptanceRule.Metropolis,
        schedule = ScheduleBundle(temperature = schedule),
        tabu = tabu,
        configurationChecking = configurationChecking,
        feasibleDescent = FeasibleDescent.RatchetAsConstraint,
    )

    /**
     * SA as a COP objective-optimizer: the focused feasibility opener plus the feasible-phase objective
     * sources ([ObjectiveSeed] + [SatisfiedStructured]) so [AcceptanceRule.Metropolis] keeps annealing
     * at `cost == 0`, stepping through worse-objective feasible states, instead of bailing to the
     * engine's greedy descent. Uses [FeasibleDescent.AnnealSelfOwned] so its own Metropolis acceptance —
     * not the engine's strict-improvement gate — owns the feasible walk.
     */
    fun optimizer(
        schedule: Schedule,
        satisfiedSampleCount: Int = 4,
        tabu: TabuFilter = TabuFilter(tenure = 10),
        configurationChecking: Boolean = false,
    ): SourceDrivenStrategy = SourceDrivenStrategy(
        sources = focusedSources() + ConfiguredSource(ObjectiveSeed()) +
            ConfiguredSource(SatisfiedStructured.sampled(satisfiedSampleCount)),
        scoring = MoveScoring.Break,
        acceptance = AcceptanceRule.Metropolis,
        schedule = ScheduleBundle(temperature = schedule),
        tabu = tabu,
        configurationChecking = configurationChecking,
        feasibleDescent = FeasibleDescent.AnnealSelfOwned,
    )
}
