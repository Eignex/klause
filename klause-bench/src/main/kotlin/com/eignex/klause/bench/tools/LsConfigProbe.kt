package com.eignex.klause.bench.tools

import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.localsearch.AdaptivePerturbationRestart
import com.eignex.klause.solver.localsearch.CostShaping
import com.eignex.klause.solver.localsearch.FixedCadenceRestart
import com.eignex.klause.solver.localsearch.IteratedLocalSearchRestart
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import com.eignex.klause.solver.localsearch.PerturbationKind
import com.eignex.klause.solver.localsearch.RestartPolicy
import com.eignex.klause.solver.localsearch.strategy.AspirationCriterion
import com.eignex.klause.solver.localsearch.strategy.Cbls
import com.eignex.klause.solver.localsearch.strategy.Strategy
import com.eignex.klause.solver.localsearch.strategy.TabuFilter
import com.eignex.klause.solver.localsearch.strategy.Vnd
import java.io.File

/**
 * Probe: does a *single* LS instance reach feasibility on an FZN under each of several
 * (strategy, restart) configs — the ones [com.eignex.klause.portfolio.LocalSearchPortfolio]
 * composes but the single-threaded CLI never engages? Reports time-to-first-feasible per
 * config. Used to test whether the large-move machinery (VND escalation, ILS perturbation
 * kicks) closes the #35 feasibility misses.
 *
 * Run: `./gradlew :klause-bench:bench --args="diag:lsconfig" -Dklause.lsprobe.file=/tmp/diag/areas.fzn -Dklause.lsprobe.ms=20000`
 */
object LsConfigProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = System.getProperty("klause.lsprobe.file") ?: args.getOrNull(0) ?: error("set -Dklause.lsprobe.file")
        val budgetMs = System.getProperty("klause.lsprobe.ms")?.toLong() ?: 20_000L
        val seeds = System.getProperty("klause.lsprobe.seeds")?.toInt() ?: 3
        val prog = parseFlatZinc(File(path).readText())
        val problem = prog.problem
        val tabu = TabuFilter(tenure = 10, aspiration = AspirationCriterion.OrImproving)
        println("=== ${File(path).name}  bool=${problem.numBoolVars} int=${problem.numIntVars} factors=${problem.numFactors} ===")

        val configs: List<Triple<String, Strategy, RestartPolicy>> = listOf(
            Triple("cbls/fixed (CLI default)", Cbls(tabu = tabu), FixedCadenceRestart()),
            Triple("cbls-smooth/ils-basin",
                Cbls(smoothProb = 0.4, smoothFactor = 0.8, tabu = tabu),
                IteratedLocalSearchRestart(populationSize = 3, crossoverRate = 0.25,
                    perturbationKind = PerturbationKind.BasinHopping, acceptance = com.eignex.klause.solver.localsearch.AcceptanceCriterion.Improving)),
            Triple("cbls/adaptive-perturb", Cbls(tabu = tabu), AdaptivePerturbationRestart()),
            Triple("vnd/ils-linkage",
                Vnd(maxNeighborhood = 3, skewAlpha = 0.2),
                IteratedLocalSearchRestart(populationSize = 5, crossoverRate = 0.4, linkageAware = true)),
        )

        for ((name, strat, restart) in configs) {
            var solvedSeeds = 0
            var bestMs = -1L
            for (s in 0 until seeds) {
                val solver = LocalSearchSolver(
                    problem, strategy = strat, optimizeStrategy = strat, restartPolicy = restart,
                )
                val start = System.currentTimeMillis()
                val deadline = start + budgetMs
                val params = LocalSearchParams(
                    randomSeed = s.toLong(),
                    costShaping = CostShaping.Linear(lambda = 1.0),
                    cancellation = Cancellation { System.currentTimeMillis() > deadline },
                )
                val sample = solver.enumerate(params).firstOrNull()
                val elapsed = System.currentTimeMillis() - start
                if (sample != null) { solvedSeeds++; if (bestMs < 0 || elapsed < bestMs) bestMs = elapsed }
            }
            println("  %-26s feasible %d/%d%s".format(name, solvedSeeds, seeds,
                if (bestMs >= 0) "  first=${bestMs}ms" else ""))
        }
    }
}
