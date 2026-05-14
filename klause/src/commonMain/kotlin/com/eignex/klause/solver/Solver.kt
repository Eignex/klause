package com.eignex.klause.solver

import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.brute.BruteForceSolver

/**
 * Marker for backend-specific solver params. Each solver backend ships its own data class
 * implementing this; the [Solver] / [Optimizer] interfaces are generic over the params type
 * so the type system enforces the right params reach the right backend.
 */
interface SolverParams

/**
 * Outcome of a single-shot [Solver.solve] call.
 *
 *  - [Sat] — the engine found a satisfying assignment.
 *  - [Unsat] — the engine proved no assignment exists. Only complete backends (LogicNG,
 *    Z3, `BruteForceSolver`, `BacktrackSolver`) return this; the local-search engine
 *    returns [Unknown] when its budget is exhausted.
 *  - [Unknown] — the engine returned without a definitive answer (LS budget exhausted,
 *    timeout, etc.).
 */
sealed interface SolveResult {
    data class Sat(val assignment: Sample) : SolveResult
    data object Unsat : SolveResult
    data object Unknown : SolveResult
}

/**
 * Backend that produces satisfying assignments for a [Problem]. Four entry points:
 *
 *  - [solve] — single-shot SAT/UNSAT/Unknown.
 *  - [sample] — first satisfying assignment, or `null` if the engine couldn't find one
 *    within its budget. Default implementation takes the first yield of [samples];
 *    backends with a cheaper one-shot path may override.
 *  - [samples] — *with replacement*. Each yield is an independent draw; the same
 *    assignment may reappear. Dedup fields on [P] (`minHammingDistance`, `recentWindow`)
 *    are ignored on this path.
 *  - [enumerate] — *without replacement*. Distinct satisfying assignments. Complete
 *    backends enumerate every model exactly once; stochastic backends honour the
 *    rolling-window dedup via `params.minHammingDistance` / `params.recentWindow`.
 */
interface Solver<P : SolverParams> {
    val problem: Problem
    fun solve(params: P): SolveResult
    fun sample(params: P): Sample? = samples(params).firstOrNull()
    fun samples(params: P): Sequence<Sample>
    fun enumerate(params: P): Sequence<Sample>
}

/**
 * A [Solver] that also returns a feasible assignment minimising a linear (or other)
 * objective.
 *
 * Calls carry the [Objective] per-invocation so the same backend can be reused across
 * differently-weighted optimisation queries (e.g. Thompson-sampled weight vectors).
 *
 * Backends typically specialise on [LinearObjective] for a fast path (incremental delta in
 * local search, native `mkAdd` translation in Z3) and either fall back to
 * [Objective.evaluate] for arbitrary subtypes or refuse to optimise them.
 */
interface Optimizer<P : SolverParams> : Solver<P> {
    /**
     * Return the lowest-objective assignment that satisfies all hard constraints, or
     * `null` if no feasible assignment was found within [params]'s budget.
     */
    fun minimize(objective: Objective, params: P): Sample?
}
