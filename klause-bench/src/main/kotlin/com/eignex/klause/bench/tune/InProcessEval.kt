package com.eignex.klause.bench.tune

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.bench.runner.ResolvedProblem
import com.eignex.klause.localsearch.FixedCadenceRestart
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.localsearch.strategy.LocalSearchRecipe
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.result.MinimizeResult

/** Outcome of a single-solver evaluation. COP: [feasible] with [objective] (minimised orientation) and
 *  whether optimality was [proven]. CSP: [feasible] (SAT) with [firstFeasibleMs] (time to the witness,
 *  the CSP metric), or [proven] = a proven UNSAT. The BO (#24) turns this into a reward per problem
 *  kind — gap-to-optimum for COP (with the reference table #26), time-to-first-feasible for CSP. */
internal data class EvalResult(
    val feasible: Boolean,
    val objective: Double?,
    val proven: Boolean,
    val firstFeasibleMs: Long? = null,
)

/**
 * In-process single-solver evaluation for the BO config search (#22). Runs one config DIRECTLY on the
 * engine — [LocalSearchSolver] / [BacktrackSolver] — never through the portfolio, so the measurement is
 * a genuine single solver (no bandit warmup / segment / re-seed artifacts) and every knob is set in
 * Kotlin (no CLI `--param` exposure needed; the CLI stays portfolio-only). A COP instance (with an
 * objective) is minimized; a CSP instance (no objective) is solved for satisfaction — a satisfy run
 * stops at the first feasible solution, so its wall-clock is the time-to-first-feasible.
 */
internal object InProcessEval {

    /** Evaluate an LS config (decoded [recipe] from [LocalSearchConfigSpace]) on [entry] for [budgetMs] at [seed]. */
    fun evalLs(entry: ResolvedProblem, recipe: LocalSearchRecipe, budgetMs: Long, seed: Long): EvalResult {
        val solver = LocalSearchSolver(
            entry.problem.bake(),
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
        val objective = entry.objective
        return if (objective != null) solver.minimize(objective, params).toEval() else satisfy { solver.solve(params) }
    }

    /** Evaluate a backtrack config ([params] from [BacktrackConfigSpace]) on [entry] for [budgetMs] at [seed]. */
    fun evalBt(entry: ResolvedProblem, params: BacktrackParams, budgetMs: Long, seed: Long): EvalResult {
        val wired = params.copy(randomSeed = seed, cancellation = deadline(budgetMs))
        val solver = BacktrackSolver(entry.problem.bake())
        val objective = entry.objective
        return if (objective != null) solver.minimize(objective, wired).toEval() else satisfy { solver.solve(wired) }
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

    /** Run a satisfy [solve] and time it: SAT carries the time-to-first-feasible; a complete backend can
     *  return a proven UNSAT; local search reports Unknown when its budget runs out. */
    private fun satisfy(solve: () -> SolveResult): EvalResult {
        val start = System.currentTimeMillis()
        val result = solve()
        val elapsed = System.currentTimeMillis() - start
        return when (result) {
            is SolveResult.Sat ->
                EvalResult(feasible = true, objective = null, proven = false, firstFeasibleMs = elapsed)

            is SolveResult.Unsat -> EvalResult(feasible = false, objective = null, proven = true)

            is SolveResult.Unknown -> EvalResult(feasible = false, objective = null, proven = false)
        }
    }
}
