package com.eignex.klause.solver.result

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.baked
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.Solver
import com.eignex.klause.solver.SolverParams
import com.eignex.klause.solver.withSourceSemantics

/**
 * Result of [satisfyUnderAssumptions]. Distinguishes a satisfying model, a hypothesis-level
 * contradiction (the assumption literals — alone or together with the hard constraints —
 * are jointly unsat), a budget cap, and a globally-unsat problem (independent of the
 * assumptions).
 *
 * Used by the core-guided MaxSAT loop ([com.eignex.klause.meta.coreguided.CoreGuidedOptimizer])
 * which drives a sequence of `satisfy(assumptions)` calls and refines its lower bound off
 * each [UnsatUnderAssumptions.core].
 */
sealed interface SatisfyResult {
    /** Effective source interpretation, with assumption restrictions retained separately. */
    val semantics: EffectiveSourceSemantics?

    /** Satisfiable, carrying a model. */
    data class Sat(
        /** The satisfying assignment. */
        val sample: Sample,
        override val semantics: EffectiveSourceSemantics? = sample.semantics,
    ) : SatisfyResult

    /** Subset of the explicit and parameter assumptions that's jointly infeasible against the problem's
     *  hard constraints. Sound (always unsat) but not necessarily minimal. For
     *  backends that can't extract a sub-core (local-search) this is the full
     *  input [Assumptions] verbatim. */
    data class UnsatUnderAssumptions(
        /** The assumption subset that caused infeasibility. */
        val core: Assumptions,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : SatisfyResult

    /** Problem is unsat even with the assumptions dropped — the assumption layer is
     *  irrelevant. Distinct from [UnsatUnderAssumptions] so the MaxSAT loop can short-
     *  circuit instead of relaxing a fictitious core. */
    data class GloballyUnsat(
        /** The unsat core, when one is available. */
        val core: UnsatCore? = null,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : SatisfyResult

    /** Indeterminate result (e.g. timeout). */
    data class Unknown(
        /** Why the result is indeterminate. */
        val reason: TerminationReason,
        override val semantics: EffectiveSourceSemantics? = null,
    ) : SatisfyResult
}

/**
 * Run `solver` with [assumptions] pinned for this call and return a [SatisfyResult].
 *
 * Parameter assumptions merge with [assumptions], with explicit pins taking precedence. On `Unsat`,
 * the merged assumption subset returned in [SatisfyResult.UnsatUnderAssumptions.core]
 * is sound (jointly infeasible with the hard constraints) but not guaranteed minimal.
 * The [BacktrackSolver] override drives the seed phase manually so seed-time conflicts
 * yield the applied prefix as a core; deeper conflicts use the engine's assumption core, falling
 * back to the full input assumption set (minimized when [minimizeCore] is set) when it has none.
 */
fun <P : SolverParams> Solver<P>.satisfyUnderAssumptions(
    assumptions: Assumptions,
    params: P,
    minimizeCore: Boolean = false,
): SatisfyResult {
    val effectiveAssumptions = params.assumptions.mergedWith(assumptions)
    if (this is BacktrackSolver && params is BacktrackParams) {
        return satisfyUnderAssumptionsBacktrack(
            this,
            effectiveAssumptions,
            params.copy(assumptions = Assumptions.None),
            minimizeCore,
        )
            .withSourceSemantics(problem.semantics)
    }
    val merged = params.withAssumptions(assumptions)
    @Suppress("UNCHECKED_CAST")
    return when (val r = solve(merged as P)) {
        is SolveResult.Sat -> SatisfyResult.Sat(r.assignment)

        is SolveResult.Unsat ->
            if (effectiveAssumptions.isEmpty && effectiveAssumptions.deductions.isEmpty) {
                SatisfyResult.GloballyUnsat(r.core)
            } else {
                val core = if (minimizeCore && params.assumptions.deductions.isEmpty) {
                    deletionMinimize(this, effectiveAssumptions, params)
                } else {
                    effectiveAssumptions
                }
                SatisfyResult.UnsatUnderAssumptions(core)
            }

        is SolveResult.Unknown -> SatisfyResult.Unknown(r.reason)
    }.withSourceSemantics(problem.semantics)
}

/**
 * Backtrack-specific path: run the bake check + seed phase by hand so a seed-time
 * conflict retains the applied prefix of [assumptions], including the transitive premises
 * of propagated deductions. If the seed succeeds we fall through
 * to a normal `solve(withAssumptions)` for the DFS phase; an UNSAT verdict there returns the
 * engine's assumption core, or the full assumption set when it reports none.
 */
private fun satisfyUnderAssumptionsBacktrack(
    solver: BacktrackSolver,
    assumptions: Assumptions,
    params: BacktrackParams,
    minimizeCore: Boolean,
): SatisfyResult {
    if (assumptions.isEmpty && assumptions.deductions.isEmpty) {
        return when (val r = solver.solve(params)) {
            is SolveResult.Sat -> SatisfyResult.Sat(r.assignment)
            is SolveResult.Unsat -> SatisfyResult.GloballyUnsat(r.core)
            is SolveResult.Unknown -> SatisfyResult.Unknown(r.reason)
        }
    }
    val problem = solver.problem
    val baked = problem.baked
    if (baked is PropagationResult.Unsat) {
        val core = if (baked.conflictFactors.isEmpty()) {
            null
        } else {
            UnsatCore.of(baked.conflictFactors)
        }
        return SatisfyResult.GloballyUnsat(core)
    }
    val session = PropagationSession(problem)
    val seedResult = session.seed(assumptions)
    if (seedResult is PropagationResult.Unsat) {
        return SatisfyResult.UnsatUnderAssumptions(
            projectSeedConflictToAssumptions(assumptions, seedResult.conflictLevels),
        )
    }
    // Engine cores retain a refuted seed prefix or the full input after tree exhaustion.
    val merged = params.withAssumptions(assumptions)
    return when (val r = solver.solve(merged)) {
        is SolveResult.Sat -> SatisfyResult.Sat(r.assignment)

        is SolveResult.Unsat -> {
            val engineCore = r.assumptionCore
            val core = when {
                !assumptions.deductions.isEmpty -> assumptions
                engineCore != null && !engineCore.isEmpty -> engineCore
                minimizeCore -> deletionMinimize(solver, assumptions, params)
                else -> assumptions
            }
            SatisfyResult.UnsatUnderAssumptions(core)
        }

        is SolveResult.Unknown -> SatisfyResult.Unknown(r.reason)
    }
}

/**
 * Deletion-based MUS minimisation: starting from [assumptions] (known unsat), try
 * dropping one literal at a time and re-solving. Literals whose removal still leaves
 * the problem unsat are not load-bearing — drop them; literals whose removal makes the
 * problem sat are kept. Result is a minimal unsat subset (any further deletion would
 * make it sat). Parameter pins are retained when the generic parameter contract cannot remove them;
 * inherited deductions disable generic minimization because deleting a pin can restore a shadowed restriction.
 *
 * O(|assumptions| × solve_cost) — opt-in via the `minimizeCore` flag on
 * [satisfyUnderAssumptions]. Recommended only when callers can tolerate the extra
 * solve calls in exchange for tighter cores (typically off-line MUS extraction; core-guided
 * MaxSAT loops should leave it off and rely on the seed-time projection alone).
 */
private fun <P : SolverParams> deletionMinimize(solver: Solver<P>, assumptions: Assumptions, params: P): Assumptions {
    // Walk both bool and int pins. We iterate over snapshots of the key arrays so the
    // working `current` can shrink as we discover removable pins.
    var current = assumptions
    val candidateBools = assumptions.boolKeys.copyOf()
    val candidateInts = assumptions.intKeys.copyOf()

    for (id in candidateBools) {
        if (params.assumptions.isFrozenBool(id)) continue
        if (current.boolValueOrNull(id) == null) continue // already dropped
        val trial = current.dropBool(id)
        if (trial.isEmpty) continue // dropping it leaves nothing — last necessary pin
        val merged = params.withAssumptions(trial)

        @Suppress("UNCHECKED_CAST")
        val r = solver.solve(merged as P)
        if (r is SolveResult.Unsat) {
            // Without this pin we're still unsat → it isn't load-bearing, drop it.
            current = trial
        }
    }
    for (id in candidateInts) {
        if (params.assumptions.isFrozenInt(id)) continue
        if (current.intValueOrNull(id) == null) continue
        val trial = current.dropInt(id)
        if (trial.isEmpty) continue
        val merged = params.withAssumptions(trial)

        @Suppress("UNCHECKED_CAST")
        val r = solver.solve(merged as P)
        if (r is SolveResult.Unsat) current = trial
    }
    return current
}

/** Sorted-array deletion helper for [Assumptions.boolKeys]. */
private fun Assumptions.dropBool(id: Int): Assumptions {
    val idx = boolKeys.toList().indexOf(id)
    if (idx < 0) return this
    val nk = IntArray(boolKeys.size - 1)
    val nv = BooleanArray(boolKeys.size - 1)
    boolKeys.copyInto(nk, 0, 0, idx)
    boolValues.copyInto(nv, 0, 0, idx)
    boolKeys.copyInto(nk, idx, idx + 1)
    boolValues.copyInto(nv, idx, idx + 1)
    return Assumptions(nk, nv, intKeys, intValues, deductions.withoutSetRestrictions())
}

/** Sorted-array deletion helper for [Assumptions.intKeys]. */
private fun Assumptions.dropInt(id: Int): Assumptions {
    val idx = intKeys.toList().indexOf(id)
    if (idx < 0) return this
    val nk = IntArray(intKeys.size - 1)
    val nv = LongArray(intKeys.size - 1)
    intKeys.copyInto(nk, 0, 0, idx)
    intValues.copyInto(nv, 0, 0, idx)
    intKeys.copyInto(nk, idx, idx + 1)
    intValues.copyInto(nv, idx, idx + 1)
    return Assumptions(boolKeys, boolValues, nk, nv, deductions.withoutSetRestrictions())
}

/**
 * Map a refuted seed prefix in [PropagationResult.Unsat.conflictLevels] back
 * to the assumption literals that live at those levels. Seed iteration order is
 * bools-first (in [Assumptions.boolKeys] ascending order, each at its own level starting
 * at 1) then ints (same shape, continuing from `numBools + 1`).
 *
 * Levels strictly outside the seed range (e.g. propagator-induced internal-level
 * deductions that conflictLevels somehow surfaces) are dropped, matching the contract
 * that the returned [Assumptions] is a subset of the input.
 */
internal fun projectSeedConflictToAssumptions(input: Assumptions, conflictLevels: IntArray): Assumptions {
    if (!input.deductions.isEmpty) return input
    val nb = input.boolKeys.size
    val ni = input.intKeys.size
    val boolHit = BooleanArray(nb)
    val intHit = BooleanArray(ni)
    for (lvl in conflictLevels) {
        val idx = lvl - 1
        if (idx in 0 until nb) {
            boolHit[idx] = true
        } else if (idx in nb until nb + ni) {
            intHit[idx - nb] = true
        }
    }
    var bc = 0
    for (h in boolHit) if (h) bc++
    var ic = 0
    for (h in intHit) if (h) ic++
    if (bc == 0 && ic == 0) {
        // Conflict surfaced but no level mapped back to a seed assumption — fall back
        // to the full input so the caller still has a sound core to refine.
        return input
    }
    val bk = IntArray(bc)
    val bv = BooleanArray(bc)
    var w = 0
    for (i in 0 until nb) {
        if (boolHit[i]) {
            bk[w] = input.boolKeys[i]
            bv[w] = input.boolValues[i]
            w++
        }
    }
    val ik = IntArray(ic)
    val iv = LongArray(ic)
    w = 0
    for (i in 0 until ni) {
        if (intHit[i]) {
            ik[w] = input.intKeys[i]
            iv[w] = input.intValues[i]
            w++
        }
    }
    return Assumptions(bk, bv, ik, iv)
}
