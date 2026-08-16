package com.eignex.klause.bench.tune

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

/**
 * The local-search config search space — the full sub-algorithm knob cross-product as a
 * lazy [ConfigSpace], decoded into an [LocalSearchRecipe] by [toRecipe]. A `family` categorical
 * (cbls/probsat/walksat/sa/fjump) gates the per-family knobs (conditional params), so a CBLS-only
 * cap is inactive for a ProbSat point. The BO searches this space directly and each evaluated point
 * builds one recipe on demand.
 */
internal object LocalSearchConfigSpace : ConfigSpace(PARAMS) {

    /** Decode a sampled assignment into a fresh [LocalSearchRecipe]. Family-routed to the public strategy
     *  factories; CBLS/SA use the unified minimize path (a second fresh strategy for the optimize
     *  phase, since strategies carry per-search state). */
    fun toRecipe(a: Map<String, Any>): LocalSearchRecipe {
        val restart = a.str("restart")
        val label = "cfg/" + a.entries.joinToString(",") { "${it.key}=${it.value}" }
        return when (a.str("family")) {
            "cbls" -> LocalSearchRecipe(
                label,
                cbls(a).withRestart(restart),
                optimizeStrategy = cbls(a).withRestart(restart),
                perMoveInvariants = a.str("cbls.augment") != "chain",
                seedImplicitOnRestart = a.str("cbls.augment") in setOf("implicit", "extended"),
            )

            "probsat" -> LocalSearchRecipe(label, probsat(a).withRestart(restart))

            "walksat" -> LocalSearchRecipe(label, walksat(a).withRestart(restart))

            "sa" -> LocalSearchRecipe(
                label,
                SimulatedAnnealing.optimizer(schedule(a.str("sa.schedule"))).withRestart(restart),
                optimizeStrategy = SimulatedAnnealing.optimizer(schedule(a.str("sa.schedule"))).withRestart(restart),
            )

            "fjump" -> LocalSearchRecipe(label, FeasibilityJump().withRestart(restart))

            else -> error("unknown family '${a.str("family")}'")
        }
    }

    private fun cbls(a: Map<String, Any>): SourceDrivenStrategy {
        val tenure = a.int("cbls.tabu")
        val tabu = if (tenure <= 0) TabuFilter.Disabled else TabuFilter(tenure, AspirationCriterion.OrImproving)
        val scoring = if (a.str("cbls.scoring") == "raw") MoveScoring.Raw else MoveScoring.Weighted
        val noise = a.dbl("cbls.noise")
        return when (a.str("cbls.augment")) {
            "plateau" -> Cbls(stallSwapCap = 16, noiseProbability = noise, tabu = tabu, scoring = scoring)

            "plateau64" -> Cbls(stallSwapCap = 64, noiseProbability = noise, tabu = tabu, scoring = scoring)

            "chain" ->
                Cbls(stallChainCap = 8, stallChainDepth = 16, noiseProbability = noise, tabu = tabu, scoring = scoring)

            "smooth" ->
                Cbls(smoothProb = 0.4, smoothFactor = 0.8, noiseProbability = noise, tabu = tabu, scoring = scoring)

            "clique" -> Cbls(stallCliqueSwapCap = 8, noiseProbability = noise, tabu = tabu, scoring = scoring)

            "flipprop" -> Cbls(flipPropagateCap = 8, noiseProbability = noise, tabu = tabu, scoring = scoring)

            "hotpair" -> Cbls(pairSwapHotSpotCap = 8, noiseProbability = noise, tabu = tabu, scoring = scoring)

            "implicit" -> Cbls(implicitStructuredCap = 8, noiseProbability = noise, tabu = tabu, scoring = scoring)

            "extended" -> Cbls(
                implicitStructuredCap = 8,
                extendedStructuredCap = 8,
                noiseProbability = noise,
                tabu = tabu,
                scoring = scoring,
            )

            else -> Cbls(noiseProbability = noise, tabu = tabu, scoring = scoring)
        }
    }

    private fun probsat(a: Map<String, Any>): SourceDrivenStrategy {
        val cb = a.dbl("probsat.cb")
        val tabu = TabuFilter(10, AspirationCriterion.OrImproving)
        return when (a.str("probsat.variant")) {
            "adaptive" -> ProbSat.adaptive(baselineCb = cb, tabu = tabu)
            "bandit" -> ProbSat.bandit(baselineCb = cb, tabu = tabu)
            else -> ProbSat(cb = cb, tabu = tabu)
        }
    }

    private fun walksat(a: Map<String, Any>): SourceDrivenStrategy {
        val cc = a.str("walksat.cc") == "true"
        val noise = a.dbl("walksat.noise")
        return if (a.str("walksat.variant") == "adaptive") {
            WalkSat.adaptive(baselineNoise = noise, configurationChecking = cc)
        } else {
            WalkSat(noise = noise, configurationChecking = cc)
        }
    }

    private fun schedule(kind: String): Schedule = when (kind) {
        "reheat" -> Reheating(Geometric(), period = 20_000)

        "phased" -> LoopSchedule(
            listOf(
                Segment(Geometric(initialTemperature = 2.0, coolingRate = 0.99), steps = 10_000),
                Segment(Geometric(initialTemperature = 0.3, coolingRate = 0.9995), steps = 40_000),
            ),
        )

        else -> Geometric()
    }

    private fun SourceDrivenStrategy.withRestart(name: String): SourceDrivenStrategy =
        copy(schedule = schedule.copy(restart = restart(name)))

    private fun restart(name: String): RestartPolicy = when (name) {
        "luby" -> LubyRestart(unit = 200)

        "perturb" -> AdaptivePerturbationRestart()

        "ils-basin" -> IteratedLocalSearchRestart(
            populationSize = 3,
            crossoverRate = 0.25,
            perturbationKind = PerturbationKind.BasinHopping,
            acceptance = AcceptanceCriterion.Improving,
        )

        else -> FixedCadenceRestart()
    }

    private fun Map<String, Any>.str(k: String) = this[k] as String
    private fun Map<String, Any>.int(k: String) = this[k] as Int
    private fun Map<String, Any>.dbl(k: String) = this[k] as Double
}

private val FAMILIES = listOf("cbls", "probsat", "walksat", "sa", "fjump")
private fun family(vararg f: String): (Map<String, Any>) -> Boolean = { it["family"] in f }

/** The declared LS space: a `family` + shared `restart`, then per-family conditional knobs. */
private val PARAMS: List<ConfigParam> = listOf(
    CategoricalParam("family", FAMILIES),
    CategoricalParam("restart", listOf("fixed", "luby", "perturb", "ils-basin")),
    CategoricalParam(
        "cbls.augment",
        listOf(
            "none", "plateau", "plateau64", "chain", "smooth",
            "clique", "flipprop", "hotpair", "implicit", "extended",
        ),
        family("cbls"),
    ),
    DoubleParam("cbls.noise", 0.0, 0.2, family("cbls")),
    IntParam("cbls.tabu", 0, 20, family("cbls")),
    CategoricalParam("cbls.scoring", listOf("weighted", "raw"), family("cbls")),
    CategoricalParam("probsat.variant", listOf("static", "adaptive", "bandit"), family("probsat")),
    DoubleParam("probsat.cb", 1.5, 3.0, family("probsat")),
    CategoricalParam("walksat.variant", listOf("fixed", "adaptive"), family("walksat")),
    DoubleParam("walksat.noise", 0.0, 0.6, family("walksat")),
    CategoricalParam("walksat.cc", listOf("false", "true"), family("walksat")),
    CategoricalParam("sa.schedule", listOf("geometric", "reheat", "phased"), family("sa")),
)
