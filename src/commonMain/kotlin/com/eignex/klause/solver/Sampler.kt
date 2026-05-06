package com.eignex.klause.solver

/**
 * Marker for backend-specific solver params. Each solver backend ships its own data class
 * implementing this; the [Solver] / [Sampler] interfaces are generic over the params type so
 * the type system enforces the right params reach the right backend.
 */
interface SolverParams

/**
 * Outcome of a single-shot [Solver.solve] call.
 *
 *  - [Sat] — the engine found a satisfying assignment.
 *  - [Unsat] — the engine proved no assignment exists. (Only complete backends like LogicNG
 *    can return this; the local-search solver returns [Unknown] when its budget is exhausted.)
 *  - [Unknown] — the engine returned without a definitive answer (LS budget exhausted, LogicNG
 *    timeout).
 */
sealed interface SolveResult {
    data class Sat(val assignment: Sample) : SolveResult
    data object Unsat : SolveResult
    data object Unknown : SolveResult
}

/** Backend that decides SAT/UNSAT for a [Problem]. */
interface Solver<P : SolverParams> {
    val problem: Problem
    fun solve(params: P): SolveResult
}

/**
 * A [Solver] that also produces a stream of satisfying assignments. Two streaming methods
 * with different semantics:
 *
 *  - [sample] — *with replacement*. Each yield is an independent draw; the same assignment
 *    can reappear. Dedup-related fields on [P] (e.g. `minHammingDistance`, `recentWindow`)
 *    are ignored on this path.
 *  - [enumerate] — *without replacement*. Distinct satisfying assignments. For complete
 *    backends this is true model enumeration (every assignment exactly once); for the
 *    local-search backend the rolling-window dedup honours `params.minHammingDistance` and
 *    `params.recentWindow`.
 */
interface Sampler<P : SolverParams> : Solver<P> {
    fun sample(params: P): Sequence<Sample>
    fun enumerate(params: P): Sequence<Sample>
}
