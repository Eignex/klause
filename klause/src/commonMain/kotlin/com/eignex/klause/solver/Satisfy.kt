package com.eignex.klause.solver

import com.eignex.klause.solver.backtrack.BacktrackSolver
import com.eignex.klause.solver.propagation.PropagationResult
import com.eignex.klause.solver.propagation.PropagationSession

/**
 * Result of [satisfyUnderAssumptions]. Distinguishes a satisfying model, a hypothesis-level
 * contradiction (the assumption literals — alone or together with the hard constraints —
 * are jointly unsat), a budget cap, and a globally-unsat problem (independent of the
 * assumptions).
 *
 * Used by the core-guided MaxSAT loop ([com.eignex.klause.solver.optimize.CoreGuidedOptimizer])
 * which drives a sequence of `satisfy(assumptions)` calls and refines its lower bound off
 * each [UnsatUnderAssumptions.core].
 */
sealed interface SatisfyResult {
    data class Sat(val sample: Sample) : SatisfyResult
    /** Subset of the input assumptions that's jointly infeasible against the problem's
     *  hard constraints. Sound (always unsat) but not necessarily minimal. For
     *  backends that can't extract a sub-core (LogicNG, Z3 without tracked assertions,
     *  local-search) this is the full input [Assumptions] verbatim. */
    data class UnsatUnderAssumptions(val core: Assumptions) : SatisfyResult
    /** Problem is unsat even with the assumptions dropped — the assumption layer is
     *  irrelevant. Distinct from [UnsatUnderAssumptions] so the MaxSAT loop can short-
     *  circuit instead of relaxing a fictitious core. */
    data class GloballyUnsat(val core: UnsatCore? = null) : SatisfyResult
    data class Unknown(val reason: TerminationReason) : SatisfyResult
}

/**
 * Run [solver] with [assumptions] pinned for this call and return a [SatisfyResult].
 *
 * On `Unsat`, the assumption subset returned in [SatisfyResult.UnsatUnderAssumptions.core]
 * is sound (jointly infeasible with the hard constraints) but not guaranteed minimal.
 * The [BacktrackSolver] override drives the seed phase manually so seed-time conflicts
 * yield a strict-subset core; deeper conflicts (after seed succeeded) fall back to the
 * full input assumption set — refining those needs assumption-resolution on learned
 * clauses, which is a follow-up.
 */
fun <P : SolverParams> Solver<P>.satisfyUnderAssumptions(
    assumptions: Assumptions,
    params: P,
): SatisfyResult {
    if (this is BacktrackSolver && params is com.eignex.klause.solver.backtrack.BacktrackParams) {
        return satisfyUnderAssumptionsBacktrack(this, assumptions, params)
    }
    val merged = params.withAssumptions(assumptions)
    @Suppress("UNCHECKED_CAST")
    return when (val r = solve(merged as P)) {
        is SolveResult.Sat -> SatisfyResult.Sat(r.assignment)
        is SolveResult.Unsat ->
            if (assumptions.isEmpty) SatisfyResult.GloballyUnsat(r.core)
            else SatisfyResult.UnsatUnderAssumptions(assumptions)
        is SolveResult.Unknown -> SatisfyResult.Unknown(r.reason)
    }
}

/**
 * Backtrack-specific path: run the bake check + seed phase by hand so a seed-time
 * conflict can be projected back to the exact subset of [assumptions] whose decision
 * levels appear in the propagation conflict set. If the seed succeeds we fall through
 * to a normal `solve(withAssumptions)` for the DFS phase; an exhaustive-UNSAT verdict
 * there returns the full assumption set (conservative but sound).
 */
private fun satisfyUnderAssumptionsBacktrack(
    solver: BacktrackSolver,
    assumptions: Assumptions,
    params: com.eignex.klause.solver.backtrack.BacktrackParams,
): SatisfyResult {
    if (assumptions.isEmpty) {
        return when (val r = solver.solve(params)) {
            is SolveResult.Sat -> SatisfyResult.Sat(r.assignment)
            is SolveResult.Unsat -> SatisfyResult.GloballyUnsat(r.core)
            is SolveResult.Unknown -> SatisfyResult.Unknown(r.reason)
        }
    }
    val problem = solver.problem
    if (problem.baked is PropagationResult.Unsat) {
        val core = if (problem.baked.conflictFactors.isEmpty()) null
        else UnsatCore.of(problem.baked.conflictFactors)
        return SatisfyResult.GloballyUnsat(core)
    }
    val session = PropagationSession(problem)
    val seedResult = session.seed(assumptions)
    if (seedResult is PropagationResult.Unsat) {
        return SatisfyResult.UnsatUnderAssumptions(
            projectSeedConflictToAssumptions(assumptions, seedResult.conflictLevels)
        )
    }
    // Seed cleanly applied — defer to the engine's normal solve path for the DFS phase.
    val merged = params.withAssumptions(assumptions)
    return when (val r = solver.solve(merged)) {
        is SolveResult.Sat -> SatisfyResult.Sat(r.assignment)
        is SolveResult.Unsat -> SatisfyResult.UnsatUnderAssumptions(assumptions)
        is SolveResult.Unknown -> SatisfyResult.Unknown(r.reason)
    }
}

/**
 * Map a [PropagationResult.Unsat.conflictLevels] set produced during the seed phase back
 * to the assumption literals that live at those levels. Seed iteration order is
 * bools-first (in [Assumptions.boolKeys] ascending order, each at its own level starting
 * at 1) then ints (same shape, continuing from `numBools + 1`).
 *
 * Levels strictly outside the seed range (e.g. propagator-induced internal-level
 * deductions that conflictLevels somehow surfaces) are dropped, matching the contract
 * that the returned [Assumptions] is a subset of the input.
 */
internal fun projectSeedConflictToAssumptions(
    input: Assumptions,
    conflictLevels: Set<Int>,
): Assumptions {
    val nb = input.boolKeys.size
    val ni = input.intKeys.size
    val boolHit = BooleanArray(nb)
    val intHit = BooleanArray(ni)
    for (lvl in conflictLevels) {
        val idx = lvl - 1
        if (idx in 0 until nb) boolHit[idx] = true
        else if (idx in nb until nb + ni) intHit[idx - nb] = true
    }
    var bc = 0; for (h in boolHit) if (h) bc++
    var ic = 0; for (h in intHit) if (h) ic++
    if (bc == 0 && ic == 0) {
        // Conflict surfaced but no level mapped back to a seed assumption — fall back
        // to the full input so the caller still has a sound core to refine.
        return input
    }
    val bk = IntArray(bc); val bv = BooleanArray(bc)
    var w = 0
    for (i in 0 until nb) if (boolHit[i]) { bk[w] = input.boolKeys[i]; bv[w] = input.boolValues[i]; w++ }
    val ik = IntArray(ic); val iv = IntArray(ic)
    w = 0
    for (i in 0 until ni) if (intHit[i]) { ik[w] = input.intKeys[i]; iv[w] = input.intValues[i]; w++ }
    return Assumptions(bk, bv, ik, iv)
}
