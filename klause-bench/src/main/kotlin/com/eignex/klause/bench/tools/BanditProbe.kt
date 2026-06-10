package com.eignex.klause.bench.tools

import com.eignex.klause.formats.flatzinc.FlatZincProgram
import com.eignex.klause.formats.flatzinc.SolveDirective
import com.eignex.klause.formats.flatzinc.parseFlatZinc
import com.eignex.klause.portfolio.EngineMix
import com.eignex.klause.portfolio.Kind
import com.eignex.klause.portfolio.PortfolioBuilder
import com.eignex.klause.portfolio.PortfolioScenario
import com.eignex.klause.portfolio.SequentialPortfolio
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.MinimizeResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SearchEvent
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackPresets
import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.backtrack.IndomainMin
import com.eignex.klause.solver.backtrack.RegressionVariableHeuristic
import com.eignex.klause.solver.backtrack.SolutionGuided
import com.eignex.klause.solver.maximizeInt
import com.eignex.klause.solver.minimizeInt
import java.io.File

/**
 * Single-threaded feasibility/anytime probe for the bandit work (#8): runs each config on each
 * FZN with one deadline and reports time-to-first-feasible, best objective, and time-to-best, so
 * the SequentialPortfolio and the LinUCB heuristic can be A/B'd against the static free search.
 *
 * Usage: `bench diag:bandit <budgetMs> <fzn>...` (default budget 60000).
 */
internal object BanditProbe {
    private class Run(val firstMs: Long?, val best: Double?, val bestMs: Long?, val nodes: Long)

    fun main(args: Array<String>) {
        val budget = args.firstOrNull()?.toLongOrNull() ?: 60_000L
        val files = args.drop(if (args.firstOrNull()?.toLongOrNull() != null) 1 else 0)
        if (files.isEmpty()) {
            println("usage: bench diag:bandit <budgetMs> <fzn>...")
            return
        }
        println("=== bandit probe (single-threaded, ${budget}ms budget) ===")
        for (path in files) {
            val program = parseFlatZinc(File(path).readText())
            val obj = objectiveOf(program)
            val name = File(path).name
            if (obj == null) {
                println("[$name] satisfy-only (skipped; probe measures COP anytime)")
                continue
            }
            println("[$name]")
            report(
                "  conflictDriven",
                runBacktrack(program.problem, obj, budget) { dl, ev ->
                    BacktrackPresets.conflictDriven(randomSeed = 3L, cancellation = dl, onEvent = ev)
                },
            )
            report(
                "  linucb        ",
                runBacktrack(program.problem, obj, budget) { dl, ev ->
                    BacktrackParams(
                        randomSeed = 3L,
                        variableHeuristic = RegressionVariableHeuristic.linUcb(seed = 3L),
                        valueHeuristic = SolutionGuided(IndomainMin),
                        phaseSaving = true,
                        lubyRestartBase = 256L,
                        cancellation = dl,
                        onEvent = ev,
                    )
                },
            )
            report(
                "  seq-mixed     ",
                runSequential(program, obj, budget, PortfolioScenario.sequential(Kind.COP, EngineMix.MIXED)),
            )
            report(
                "  seq-backtrack ",
                runSequential(program, obj, budget, PortfolioScenario.sequential(Kind.COP, EngineMix.BACKTRACK)),
            )
        }
    }

    private fun objectiveOf(program: FlatZincProgram): LinearObjective? {
        val (objName, maximize) = when (val s = program.solve) {
            is SolveDirective.Minimize -> s.objVar to false
            is SolveDirective.Maximize -> s.objVar to true
            is SolveDirective.Satisfy -> return null
        }
        val id = program.intVarsByName[objName] ?: return null
        return if (maximize) program.problem.maximizeInt(id) else program.problem.minimizeInt(id)
    }

    private fun runBacktrack(
        problem: Problem,
        obj: LinearObjective,
        budget: Long,
        params: (Cancellation, (SearchEvent) -> Unit) -> BacktrackParams,
    ): Run {
        val t0 = System.currentTimeMillis()
        val deadline = t0 + budget
        var firstMs: Long? = null
        var bestMs: Long? = null
        val ev = { e: SearchEvent ->
            if (e is SearchEvent.Incumbent) {
                val now = System.currentTimeMillis() - t0
                if (firstMs == null) firstMs = now
                bestMs = now
            }
        }
        val r = BacktrackSolver(
            problem,
        ).minimize(obj, params(Cancellation { System.currentTimeMillis() > deadline }, ev))
        return Run(firstMs, valueOf(r), bestMs, r.stats.nodes.sum.toLong())
    }

    private fun runSequential(
        program: FlatZincProgram,
        obj: LinearObjective,
        budget: Long,
        scenario: PortfolioScenario,
    ): Run {
        val t0 = System.currentTimeMillis()
        val deadline = t0 + budget
        var firstMs: Long? = null
        var bestMs: Long? = null
        val workers = PortfolioBuilder.build(
            program.problem,
            scenario,
            objective = obj,
            lsObjective = program.lsObjective,
            definitionalSweep = program.definitionalSweep,
        )
        val seq = SequentialPortfolio.exp3(workers)
        val r = seq.use {
            it.minimize(Cancellation { System.currentTimeMillis() > deadline }) {
                val now = System.currentTimeMillis() - t0
                if (firstMs == null) firstMs = now
                bestMs = now
            }
        }
        return Run(firstMs, valueOf(r), bestMs, r.stats.nodes.sum.toLong())
    }

    private fun valueOf(r: MinimizeResult): Double? = when (r) {
        is MinimizeResult.Optimal -> r.objectiveValue
        is MinimizeResult.BestFound -> r.objectiveValue
        else -> null
    }

    private fun report(label: String, run: Run) {
        val feas = run.firstMs?.let { "feasible@${it}ms" } ?: "NO FEASIBLE"
        val best = run.best?.let { "best=$it@${run.bestMs}ms" } ?: "-"
        println("$label  $feas  $best  nodes=${run.nodes}")
    }
}
