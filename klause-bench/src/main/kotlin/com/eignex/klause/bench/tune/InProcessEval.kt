package com.eignex.klause.bench.tune

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.strategy.LsRecipe
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.result.MinimizeResult

/** Outcome of a single-solver evaluation: [feasible] with [objective] (in the minimized orientation),
 *  and whether optimality was [proven]. The gap-to-optimum reward (#22) combines this with the
 *  reference table (#26); the BO (#24) turns the reward into a Vizier measurement. */
internal data class EvalResult(val feasible: Boolean, val objective: Double?, val proven: Boolean)

/**
 * In-process single-solver evaluation for the BO config search (#22). Runs one config DIRECTLY on the
 * engine — [LocalSearchSolver] / [BacktrackSolver] — never through the portfolio, so the measurement
 * is a genuine single solver (no bandit warmup / segment / re-seed artifacts) and every knob is set in
 * Kotlin (no CLI `--param` exposure needed; the CLI stays portfolio-only). COP only: [minimize] needs
 * an objective, which is also what attribution/best-holder scoring requires.
 */
internal object InProcessEval {

    /** Evaluate an LS config (decoded [recipe] from [LocalSearchConfigSpace]) on [entry] for [budgetMs] at [seed]. */
    fun evalLs(entry: ResolvedProblem, recipe: LsRecipe, budgetMs: Long, seed: Long): EvalResult {
        val objective = requireNotNull(entry.objective) { "evalLs needs a COP instance" }
        val solver = LocalSearchSolver(
            entry.problem,
            strategy = recipe.strategy,
            optimizeStrategy = recipe.optimizeStrategy,
            restartPolicy = recipe.strategy.schedule.restart ?: FixedCadenceRestart(),
            definitionalSweep = entry.definitionalSweep,
            // per-move invariants require a definitional sweep; gate so a null sweep can't trip the require.
            perMoveInvariants = recipe.perMoveInvariants && entry.definitionalSweep != null,
            seedImplicitOnRestart = recipe.seedImplicitOnRestart,
        )
        val params = LocalSearchParams(
            randomSeed = seed,
            cancellation = deadline(budgetMs),
            lsObjective = entry.lsObjective,
        )
        return solver.minimize(objective, params).toEval()
    }

    /** Evaluate a backtrack config ([params] from [BacktrackConfigSpace]) on [entry] for [budgetMs] at [seed]. */
    fun evalBt(entry: ResolvedProblem, params: BacktrackParams, budgetMs: Long, seed: Long): EvalResult {
        val objective = requireNotNull(entry.objective) { "evalBt needs a COP instance" }
        val wired = params.copy(randomSeed = seed, cancellation = deadline(budgetMs))
        return BacktrackSolver(entry.problem).minimize(objective, wired).toEval()
    }

    private fun deadline(budgetMs: Long): Cancellation {
        val end = System.currentTimeMillis() + budgetMs
        return Cancellation { System.currentTimeMillis() > end }
    }

    private fun MinimizeResult.toEval(): EvalResult = when (this) {
        is MinimizeResult.Optimal -> EvalResult(feasible = true, objective = objective, proven = true)
        is MinimizeResult.BestFound -> EvalResult(feasible = true, objective = objective, proven = false)
        is MinimizeResult.Infeasible -> EvalResult(feasible = false, objective = null, proven = true)
        is MinimizeResult.Unknown -> EvalResult(feasible = false, objective = null, proven = false)
    }
}
